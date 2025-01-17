package com.wavesplatform.it.sync.smartcontracts

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.api.SyncMatcherHttpApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.sync.config.MatcherPriceAssetConfig._
import com.wavesplatform.it.util._
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.IssueTransactionV1
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}

import scala.concurrent.duration._

class OrdersFromScriptedAccTestSuite extends MatcherSuiteBase {

  import OrdersFromScriptedAccTestSuite._

  override protected def nodeConfigs: Seq[Config] = updatedConfigs

  private val sDupNames =
    """let x = (let x = 2
      |3)
      |x == 3""".stripMargin

  private val aliceAssetTx = IssueTransactionV1
    .selfSigned(
      sender = alice,
      name = "AliceCoin".getBytes(),
      description = "AliceCoin for matcher's tests".getBytes(),
      quantity = someAssetAmount,
      decimals = 0,
      reissuable = false,
      fee = issueFee,
      timestamp = System.currentTimeMillis()
    )
    .explicitGet()

  private val aliceAsset = aliceAssetTx.id().toString
  private val aliceWavesPair = AssetPair(IssuedAsset(ByteStr.decodeBase58(aliceAsset).get), Waves)

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    setContract(Some("true"), bob)
    node.waitForTransaction(node.broadcastRequest(aliceAssetTx.json()).id)
    node.assertAssetBalance(alice.address, aliceAsset, someAssetAmount)
    node.assertAssetBalance(matcher.address, aliceAsset, 0)
  }

  "issue asset and run test" - {
    "trading is deprecated" in {
      assertBadRequestAndResponse(
        node.placeOrder(bob, aliceWavesPair, OrderType.BUY, 500, 2.waves * Order.PriceConstant, smartTradeFee, version = 1, 10.minutes),
        "An account's feature isn't yet supported"
      )
    }

    "can't place an OrderV2 before the activation" in {
      assertBadRequestAndResponse(
        node.placeOrder(bob, aliceWavesPair, OrderType.BUY, 500, 2.waves * Order.PriceConstant, smartTradeFee, version = 2, 10.minutes),
        "The order of version .* isn't yet supported"
      )
    }

    "invalid setScript at account" in {
      node.waitForHeight(activationHeight, 5.minutes)
      setContract(Some("true && (height > 0)"), bob)
      assertBadRequestAndResponse(
        node.placeOrder(bob, aliceWavesPair, OrderType.BUY, 500, 2.waves * Order.PriceConstant, smartTradeFee, version = 2, 10.minutes),
        "An access to the blockchain.height is denied on DEX"
      )
    }

    "scripted account can trade once SmartAccountTrading is activated" in {
      setContract(Some(sDupNames), bob)
      val bobOrder =
        node.placeOrder(bob, aliceWavesPair, OrderType.BUY, 500, 2.waves * Order.PriceConstant, smartTradeFee, version = 2, 10.minutes)
      bobOrder.status shouldBe "OrderAccepted"
    }

    "scripted dApp account can trade" in {
      val script =
        """
          |
          | {-# STDLIB_VERSION 3       #-}
          | {-# CONTENT_TYPE   DAPP    #-}
          | {-# SCRIPT_TYPE    ACCOUNT #-}
          |
          | @Callable(i)
          | func call() = WriteSet([])
          |
        """.stripMargin
      setContract(Some(script), bob)

      val bobOrder = node.placeOrder(
        sender     = bob,
        pair       = aliceWavesPair,
        orderType  = OrderType.BUY,
        amount     = 500,
        price      = 2.waves * Order.PriceConstant,
        fee        = smartTradeFee,
        version    = 2,
        timeToLive = 10.minutes
      )
      bobOrder.status shouldBe "OrderAccepted"
    }

    val orderFilterScript =
      """
        |
        | {-# STDLIB_VERSION 3       #-}
        | {-# CONTENT_TYPE   DAPP    #-}
        | {-# SCRIPT_TYPE    ACCOUNT #-}
        |
        | @Verifier(tx)
        | func verify() =
        |    match tx {
        |        case o: Order => o.amount > 1000
        |        case _        => true
        |    }
        |
      """.stripMargin

    "scripted dApp account accept correct order" in {
      setContract(Some(orderFilterScript), bob)
      val bobOrder = node.placeOrder(
        sender     = bob,
        pair       = aliceWavesPair,
        orderType  = OrderType.BUY,
        amount     = 2000,
        price      = 2.waves * Order.PriceConstant,
        fee        = smartTradeFee,
        version    = 2,
        timeToLive = 10.minutes
      )
      bobOrder.status shouldBe "OrderAccepted"
    }

    "scripted dApp account reject incorrect order" in {
      setContract(Some(orderFilterScript), bob)
      node.expectRejectedOrderPlacement(
        sender     = bob,
        pair       = aliceWavesPair,
        orderType  = OrderType.BUY,
        amount     = 500,
        price      = 2.waves * Order.PriceConstant,
        fee        = smartTradeFee,
        version    = 2,
        timeToLive = 10.minutes
      ) shouldBe true
    }

    "can trade from non-scripted account" in {
      // Alice places sell order
      val aliceOrder =
        node.placeOrder(alice, aliceWavesPair, OrderType.SELL, 500, 2.waves * Order.PriceConstant, matcherFee, version = 1, 10.minutes)

      aliceOrder.status shouldBe "OrderAccepted"

      val orderId = aliceOrder.message.id
      // Alice checks that the order in order book
      node.waitOrderStatus(aliceWavesPair, orderId, "Filled")
      node.fullOrderHistory(alice).head.status shouldBe "Filled"
    }
  }
}

object OrdersFromScriptedAccTestSuite {
  val activationHeight = 10

  private val matcherConfig = ConfigFactory.parseString(s"""
                                                           |waves {
                                                           |  utx.allow-skip-checks = false
                                                           |
                                                           |  blockchain.custom.functionality.pre-activated-features = {
                                                           |    ${BlockchainFeatures.SmartAccountTrading.id} = $activationHeight,
                                                           |    ${BlockchainFeatures.SmartAssets.id} = 1000
                                                           |  }
                                                           |}""".stripMargin)

  private val updatedConfigs: Seq[Config] = Configs.map(matcherConfig.withFallback(_))
}
