package scorex.lagonaki.server

import java.io.{File, RandomAccessFile}
import java.nio.file.{Files, Paths}

import akka.actor.Props
import com.typesafe.config.ConfigFactory
import scorex.api.http._
import scorex.app.Application
import scorex.consensus.nxt.NxtLikeConsensusModule
import scorex.consensus.nxt.api.http.NxtConsensusApiRoute
import scorex.consensus.qora.QoraLikeConsensusModule
import scorex.consensus.qora.api.http.QoraConsensusApiRoute
import scorex.crypto.ads.merkle.{AuthDataBlock, MerkleTree}
import scorex.lagonaki.api.http.{PaymentApiRoute, PeersHttpService, ScorexApiRoute}
import scorex.network._
import scorex.perma.api.http.PermaConsensusApiRoute
import scorex.perma.consensus.PermaConsensusModule
import scorex.perma.network.SegmentsSynchronizer
import scorex.perma.settings.Constants
import scorex.perma.settings.Constants._
import scorex.perma.storage.AuthDataStorage
import scorex.storage.Storage
import scorex.transaction._

import scala.reflect.runtime.universe._

class LagonakiApplication(val settingsFilename: String) extends Application {

  override val applicationName = "lagonaki"

  private val appConf = ConfigFactory.load().getConfig("app")

  override implicit lazy val settings = new LagonakiSettings(settingsFilename)

  override implicit lazy val consensusModule =
    appConf.getString("consensusAlgo") match {
      case s: String if s.equalsIgnoreCase("nxt") =>
        new NxtLikeConsensusModule
      case s: String if s.equalsIgnoreCase("qora") =>
        new QoraLikeConsensusModule
      case s: String if s.equalsIgnoreCase("perma") =>
        val authDataStorage: Storage[Long, AuthDataBlock[DataSegment]] = new AuthDataStorage(settings.authDataStorage)
        if (settings.isTrustedDealer) {
          val tree = if (Files.exists(Paths.get(settings.treeDir + "/tree0.mapDB"))) {
            log.info("Get existing tree")
            new MerkleTree(settings.treeDir, Constants.n, Constants.segmentSize, Constants.hash)
          } else {
            log.info("Generating random data set")
            val treeDir = new File(settings.treeDir)
            treeDir.mkdirs()
            val datasetFile = settings.treeDir + "/data.file"
            new RandomAccessFile(datasetFile, "rw").setLength(Constants.n * Constants.segmentSize)
            log.info("Calculate tree")
            val tree = MerkleTree.fromFile(datasetFile, settings.treeDir, Constants.segmentSize, Constants.hash)
            require(tree.nonEmptyBlocks == Constants.n, s"${tree.nonEmptyBlocks} == ${Constants.n}")
            tree
          }
          require(settings.rootHash sameElements tree.rootHash, "Tree root hash differs from root hash in settings")
          log.info("Test tree")
          val index = Constants.n - 3
          val leaf = tree.byIndex(index).get
          require(leaf.check(index, tree.rootHash)(Constants.hash))

          log.info("Put ALL data to local storage")
          new File(settings.treeDir).mkdirs()
          def addBlock(i: Long): Unit = {
            authDataStorage.set(i, tree.byIndex(i).get)
            if (i > 0) {
              addBlock(i - 1)
            }
          }
          addBlock(Constants.n - 1)
        }
        val rootHash = settings.rootHash

        actorSystem.actorOf(Props(classOf[SegmentsSynchronizer], this, rootHash, authDataStorage))

        log.info("Create consensus module")
        new PermaConsensusModule(rootHash)(authDataStorage)
      case nonsense =>
        sys.error(s"Unknown consensus algo: $nonsense")
    }

  override implicit lazy val transactionModule: SimpleTransactionModule = new SimpleTransactionModule()(settings, this)

  override lazy val blockStorage = transactionModule.blockStorage

  lazy val consensusApiRoute = consensusModule match {
    case ncm: NxtLikeConsensusModule =>
      new NxtConsensusApiRoute(ncm, blockStorage)
    case qcm: QoraLikeConsensusModule =>
      new QoraConsensusApiRoute(qcm, blockStorage)
    case pcm: PermaConsensusModule =>
      new PermaConsensusApiRoute(pcm, blockStorage)
  }

  override lazy val apiRoutes = Seq(
    BlocksApiRoute(blockStorage.history, wallet),
    TransactionsApiRoute(blockStorage.state),
    consensusApiRoute,
    WalletApiRoute(wallet),
    PaymentApiRoute(this),
    ScorexApiRoute(this),
    SeedApiRoute(),
    PeersHttpService(this),
    AddressApiRoute(wallet, blockStorage.state)
  )

  override lazy val apiTypes = Seq(
    typeOf[BlocksApiRoute],
    typeOf[TransactionsApiRoute],
    consensusApiRoute match {
      case nxt: NxtConsensusApiRoute => typeOf[NxtConsensusApiRoute]
      case qora: QoraConsensusApiRoute => typeOf[QoraConsensusApiRoute]
      case pcm: PermaConsensusApiRoute => typeOf[PermaConsensusApiRoute]
    },
    typeOf[WalletApiRoute],
    typeOf[PaymentApiRoute],
    typeOf[ScorexApiRoute],
    typeOf[SeedApiRoute],
    typeOf[PeersHttpService],
    typeOf[AddressApiRoute]
  )

  override lazy val additionalMessageSpecs = TransactionalMessagesRepo.specs

  //checks
  require(transactionModule.balancesSupport)
  require(transactionModule.accountWatchingSupport)

  actorSystem.actorOf(Props(classOf[UnconfirmedPoolSynchronizer], this))
}