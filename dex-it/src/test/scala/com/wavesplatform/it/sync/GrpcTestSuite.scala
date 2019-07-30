package com.wavesplatform.it.sync

import java.nio.charset.StandardCharsets

import com.github.ghik.silencer.silent
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.account.{AddressScheme, KeyPair}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.dex.model.BriefAssetDescription
import com.wavesplatform.dex.waves.WavesBlockchainContext.RunScriptResult
import com.wavesplatform.dex.waves.WavesBlockchainGrpcContext
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.it.NodeConfigs.Default
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.sync.config.MatcherPriceAssetConfig.{matcher, _}
import com.wavesplatform.it.util._
import com.wavesplatform.it.{Docker, MatcherSuiteBase}
import com.wavesplatform.lang.script.v1.ExprScript
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.IssueTransactionV2
import com.wavesplatform.transaction.assets.exchange.{AssetPair, ExchangeTransactionV2, Order}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.transfer.TransferTransactionV2
import io.grpc.ManagedChannelBuilder

import scala.concurrent.duration.DurationInt
import scala.util.Random

class GrpcTestSuite extends MatcherSuiteBase {

  override protected def nodeConfigs: Seq[Config] = Seq(
    ConfigFactory.parseString("""|
      |waves {
      |  network.node-name = node02
      |  extensions = [ "com.wavesplatform.dex.GrpcServerExtension" ]
      |
      |  grpc {
      |    host = 0.0.0.0
      |    port = 6870
      |  }
      |}""".stripMargin).withFallback(Default.head)
  )

  private def dockerNode: Docker.DockerNode = dockerNodes().head

  private lazy val context = mkContext

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    node.signedBroadcast(IssueEthTx.json())
    node.waitForTransaction(IssueEthTx.id().toString)
  }

  "wasForged" - {
    val tx = IssueBtcTx
    val id = tx.id()

    "false for unknown tx" in {
      context.wasForged(id) shouldBe false
    }

    "true for forged tx" in {
      node.signedBroadcast(tx.json())

      node.waitForTransaction(id.toString)
      context.wasForged(id) shouldBe true
    }
  }

  "broadcastTx" - {
    "returns true if the transaction passed the validation and was added to the UTX pool" in {
      val now = System.currentTimeMillis()

      val executedAmount = 1L
      val executedPrice  = 2 * Order.PriceConstant
      val pair           = AssetPair.createAssetPair(IssueEthTx.id().base58, "WAVES").get
      val buy            = Order.buy(bob, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, matcherFee)
      val sell           = Order.sell(alice, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, matcherFee)

      val exchangeTx = ExchangeTransactionV2
        .create(
          matcher = matcher,
          buyOrder = buy,
          sellOrder = sell,
          amount = executedAmount,
          price = executedPrice,
          buyMatcherFee = matcherFee,
          sellMatcherFee = matcherFee,
          fee = matcherFee,
          timestamp = now
        )
        .right
        .get

      context.broadcastTx(exchangeTx) shouldBe true
      node.waitForTransaction(exchangeTx.id().toString)
    }

    "returns false if the transaction didn't pass the validation" in {
      val now = System.currentTimeMillis()

      val executedAmount = 1L
      val executedPrice  = 2 * Order.PriceConstant
      val pair           = AssetPair.createAssetPair(IssueEthTx.id().base58, "WAVES").get
      val buy            = Order.buy(alice, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, matcherFee)
      val sell = Order.sell(KeyPair("fake-bob".getBytes(StandardCharsets.UTF_8)),
                            matcher,
                            pair,
                            executedAmount,
                            executedPrice,
                            now,
                            now + 1.day.toMillis,
                            matcherFee)

      val exchangeTx = ExchangeTransactionV2
        .create(
          matcher = matcher,
          buyOrder = buy,
          sellOrder = sell,
          amount = executedAmount,
          price = executedPrice,
          buyMatcherFee = matcherFee,
          sellMatcherFee = matcherFee,
          fee = matcherFee,
          timestamp = now
        )
        .explicitGet()

      context.broadcastTx(exchangeTx) shouldBe false
    }
  }

  "isFeatureActivated" - {
    "returns false for not yet activated feature" in {
      context.isFeatureActivated(BlockchainFeatures.SmallerMinimalGeneratingBalance.id) shouldBe false
    }

    "returns true for activated feature" in {
      context.isFeatureActivated(BlockchainFeatures.NG.id) shouldBe true
    }
  }

  "assetDescription" - {
    "returns None if there is no such asset" in {
      context.assetDescription(IssuedAsset(IssueUsdTx.id())) shouldBe None
    }

    "returns an information for created assets" in {
      val issueTx = IssueEthTx
      context.assetDescription(IssuedAsset(issueTx.id())) shouldBe Some(
        BriefAssetDescription(
          name = ByteStr(issueTx.name),
          decimals = issueTx.decimals,
          hasScript = issueTx.script.nonEmpty
        ))
    }
  }

  "hasScript/runScript(IssuedAsset)" - {
    "hasn't a script" in {
      context.hasScript(IssuedAsset(IssueEthTx.id())) shouldBe false
    }

    "has a script" in {
      val issueTx = IssueTransactionV2
        .selfSigned(
          AddressScheme.current.chainId,
          sender = bob,
          name = "SmartCoin".getBytes(),
          description = "Actually, not".getBytes(),
          quantity = defaultAssetQuantity,
          decimals = 8,
          reissuable = false,
          script = Some(ExprScript(Terms.TRUE).explicitGet()),
          fee = 1.waves,
          timestamp = System.currentTimeMillis()
        )
        .explicitGet()

      withClue("issue scripted asset") {
        node.broadcastRequest(issueTx.json())
        node.waitForTransaction(issueTx.id().toString)

        context.hasScript(IssuedAsset(issueTx.id())) shouldBe true
      }

      withClue("run script") {
        val now            = System.currentTimeMillis()
        val executedAmount = 1L
        val executedPrice  = 2 * Order.PriceConstant
        val pair           = AssetPair.createAssetPair(issueTx.id().base58, "WAVES").get
        val buy            = Order.buy(bob, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, matcherFee)
        val sell           = Order.sell(alice, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, matcherFee)

        val exchangeTx = ExchangeTransactionV2
          .create(
            matcher = matcher,
            buyOrder = buy,
            sellOrder = sell,
            amount = executedAmount,
            price = executedPrice,
            buyMatcherFee = matcherFee,
            sellMatcherFee = matcherFee,
            fee = matcherFee,
            timestamp = now
          )
          .explicitGet()

        context.runScript(IssuedAsset(issueTx.id()), exchangeTx) shouldBe RunScriptResult.Allowed
      }
    }
  }

  "hasScript/runScript(Address)" - {
    "returns false if there is no script" in {
      context.hasScript(matcher.toAddress) shouldBe false
    }

    "returns true if a script was assigned" in {
      val receiver = KeyPair("receiver".getBytes(StandardCharsets.UTF_8))

      withClue("transfer") {
        val transferTx = TransferTransactionV2
          .selfSigned(
            assetId = Waves,
            sender = alice,
            recipient = receiver.toAddress,
            amount = 5.waves,
            timestamp = System.currentTimeMillis(),
            feeAssetId = Waves,
            feeAmount = 0.001.waves,
            attachment = Array.emptyByteArray
          )
          .explicitGet()

        node.broadcastRequest(transferTx.json())
        node.waitForTransaction(transferTx.id().toString)
      }

      withClue("set script") {
        val setScriptTx = SetScriptTransaction
          .selfSigned(
            sender = receiver,
            script = Some(ExprScript(Terms.TRUE).explicitGet()),
            fee = 0.01.waves,
            timestamp = System.currentTimeMillis()
          )
          .explicitGet()

        node.broadcastRequest(setScriptTx.json())
        node.waitForTransaction(setScriptTx.id().toString)

        context.hasScript(receiver.toAddress) shouldBe true
      }

      withClue("run script") {
        val now            = System.currentTimeMillis()
        val executedAmount = 1L
        val executedPrice  = 2 * Order.PriceConstant
        val pair           = AssetPair.createAssetPair(IssueEthTx.id().base58, "WAVES").get
        val buy            = Order.buy(bob, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, 0)

        context.runScript(receiver.toAddress, buy) shouldBe RunScriptResult.Allowed
      }
    }
  }

  "spendableBalance" in {
    context.spendableBalance(bob.toAddress, Waves) shouldBe 0L
    context.spendableBalance(bob.toAddress, randomIssuedAsset) shouldBe 0L
  }

  "forgedOrder" - {
    "no such order" in {
      context.forgedOrder(randomByteStr(32)) shouldBe false
    }

    "the order was in a forged ExchangeTransaction" in {
      val now = System.currentTimeMillis()

      val executedAmount = 1L
      val executedPrice  = 2 * Order.PriceConstant
      val pair           = AssetPair.createAssetPair(IssueEthTx.id().base58, "WAVES").get
      val buy            = Order.buy(bob, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, matcherFee)
      val sell           = Order.sell(alice, matcher, pair, executedAmount, executedPrice, now, now + 1.day.toMillis, matcherFee)

      val exchangeTx = ExchangeTransactionV2
        .create(
          matcher = matcher,
          buyOrder = buy,
          sellOrder = sell,
          amount = executedAmount,
          price = executedPrice,
          buyMatcherFee = matcherFee,
          sellMatcherFee = matcherFee,
          fee = matcherFee,
          timestamp = now
        )
        .right
        .get

      node.broadcastRequest(exchangeTx.json())
      node.waitForTransaction(exchangeTx.id().toString)

      context.forgedOrder(buy.id()) shouldBe true
      context.forgedOrder(sell.id()) shouldBe true
    }
  }

  // TODO check that the functions returns new data after the state is changed?

  @silent("deprecated") private def mkContext: WavesBlockchainGrpcContext = new WavesBlockchainGrpcContext(
    matcherAddress = IssueEthTx.sender.toAddress,
    ManagedChannelBuilder
      .forAddress(
        dockerNode.networkAddress.getHostString,
        dockerNode.nodeExternalPort(6870)
      )
      .usePlaintext(true)
      .build()
  )

  private def randomByteStr(len: Int): ByteStr = {
    val inner = new Array[Byte](len)
    Random.nextBytes(inner)
    ByteStr(inner)
  }

  private def randomIssuedAsset: IssuedAsset = IssuedAsset(randomByteStr(32))
}