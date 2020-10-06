package com.wavesplatform

import java.io.{File, FileNotFoundException}
import java.nio.file.Files

import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.{Address, AddressScheme, Alias, KeyPair}
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.block.{Block, SignedBlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.consensus.PoSCalculator.{generationSignature, hit}
import com.wavesplatform.consensus.{FairPoSCalculator, NxtPoSCalculator, PoSCalculator}
import com.wavesplatform.crypto._
import com.wavesplatform.features.{BlockchainFeature, BlockchainFeatures}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.settings.{BlockchainSettings, FunctionalitySettings, GenesisSettings, GenesisTransactionSettings}
import com.wavesplatform.state._
import com.wavesplatform.state.reader.LeaseDetails
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{Asset, GenesisTransaction, Transaction}
import com.wavesplatform.utils._
import com.wavesplatform.wallet.Wallet
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._

import scala.annotation.tailrec
import scala.concurrent.duration._

object GenesisBlockGenerator extends App {

  private type SeedText = String
  private type Share    = Long

  case class DistributionItem(seedText: String, nonce: Int, amount: Share, miner: Boolean = true)

  case class Settings(
      networkType: String,
      baseTarget: Option[Long],
      averageBlockDelay: FiniteDuration,
      timestamp: Option[Long],
      distributions: List[DistributionItem],
      preActivatedFeatures: Option[List[Int]],
      minBlockTime: Option[FiniteDuration],
      delayDelta: Option[Int]
  ) {

    val initialBalance: Share = distributions.map(_.amount).sum

    val chainId: Byte = networkType.head.toByte

    private val features: Map[Short, Int] =
      preActivatedFeatures.getOrElse(List(BlockchainFeatures.FairPoS.id.toInt, BlockchainFeatures.BlockV5.id.toInt)).map(f => f.toShort -> 0).toMap

    val functionalitySettings: FunctionalitySettings = FunctionalitySettings(
      Int.MaxValue,
      Int.MaxValue,
      preActivatedFeatures = features,
      doubleFeaturesPeriodsAfterHeight = Int.MaxValue,
      minBlockTime = minBlockTime.getOrElse(15.seconds),
      delayDelta = delayDelta.getOrElse(8)
    )

    def preActivated(feature: BlockchainFeature): Boolean = features.contains(feature.id)
  }

  case class FullAddressInfo(
      seedText: SeedText,
      seed: ByteStr,
      accountSeed: ByteStr,
      accountPrivateKey: ByteStr,
      accountPublicKey: ByteStr,
      accountAddress: Address,
      account: KeyPair,
      miner: Boolean
  )

  private def toFullAddressInfo(item: DistributionItem): FullAddressInfo = {
    val seedHash = item.seedText.utf8Bytes
    val acc      = Wallet.generateNewAccount(seedHash, item.nonce)

    FullAddressInfo(
      seedText = item.seedText,
      seed = ByteStr(seedHash),
      accountSeed = ByteStr(acc.seed),
      accountPrivateKey = acc.privateKey,
      accountPublicKey = acc.publicKey,
      accountAddress = acc.toAddress,
      acc,
      item.miner
    )
  }

  val inputConfFile = new File(args.headOption.getOrElse(throw new IllegalArgumentException("Specify a path to genesis.conf")))
  if (!inputConfFile.exists()) throw new FileNotFoundException(inputConfFile.getCanonicalPath)

  val outputConfFile = args
    .drop(1)
    .headOption
    .map(new File(_).getAbsoluteFile.ensuring(f => !f.isDirectory && f.getParentFile.isDirectory || f.getParentFile.mkdirs()))

  val settings: Settings = {
    import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase
    ConfigFactory.parseFile(inputConfFile).as[Settings]("genesis-generator")
  }

  com.wavesplatform.account.AddressScheme.current = new AddressScheme {
    override val chainId: Byte = settings.chainId
  }

  val shares: Seq[(FullAddressInfo, Share)] = settings.distributions
    .map(x => (toFullAddressInfo(x), x.amount))
    .sortBy(_._2)

  val timestamp = settings.timestamp.getOrElse(System.currentTimeMillis())

  val genesisTxs: Seq[GenesisTransaction] = shares.map {
    case (addrInfo, part) =>
      GenesisTransaction(addrInfo.accountAddress, part, timestamp, ByteStr.empty, settings.chainId)
  }

  report(
    addrInfos = shares.map(_._1),
    settings = genesisSettings(settings.baseTarget)
  )

  private def report(addrInfos: Iterable[FullAddressInfo], settings: GenesisSettings): Unit = {
    val output = new StringBuilder(8192)
    output.append("Addresses:\n")
    addrInfos.foreach { acc =>
      output.append(s"""
             | Seed text:           ${acc.seedText}
             | Seed:                ${acc.seed}
             | Account seed:        ${acc.accountSeed}
             | Private account key: ${acc.accountPrivateKey}
             | Public account key:  ${acc.accountPublicKey}
             | Account address:     ${acc.accountAddress}
             | ===
             |""".stripMargin)
    }

    val confBody = s"""genesis {
         |  average-block-delay = ${settings.averageBlockDelay.toMillis}ms
         |  initial-base-target = ${settings.initialBaseTarget}
         |  timestamp = ${settings.timestamp}
         |  block-timestamp = ${settings.blockTimestamp}
         |  signature = "${settings.signature.get}"
         |  initial-balance = ${settings.initialBalance}
         |  transactions = [
         |    ${settings.transactions.map(x => s"""{recipient = "${x.recipient}", amount = ${x.amount}}""").mkString(",\n    ")}
         |  ]
         |}
         |""".stripMargin

    output.append("Settings:\n")
    output.append(confBody)
    System.out.print(output.result())
    outputConfFile.foreach(ocf => Files.write(ocf.toPath, confBody.utf8Bytes))
  }

  def genesisSettings(predefined: Option[Long]): GenesisSettings =
    predefined
      .map(baseTarget => mkGenesisSettings(baseTarget))
      .getOrElse(mkGenesisSettings(calcInitialBaseTarget()))

  def mkGenesisSettings(baseTarget: Long): GenesisSettings = {
    val reference     = ByteStr(Array.fill(SignatureLength)(-1: Byte))
    val genesisSigner = KeyPair(ByteStr.empty)

    val genesis = Block
      .buildAndSign(
        version = 1,
        timestamp = timestamp,
        reference = reference,
        baseTarget,
        ByteStr(Array.fill(crypto.DigestLength)(0: Byte)),
        txs = genesisTxs,
        signer = genesisSigner,
        featureVotes = Seq.empty,
        rewardVote = -1L
      )
      .explicitGet()

    GenesisSettings(
      genesis.header.timestamp,
      timestamp,
      settings.initialBalance,
      Some(genesis.signature),
      genesisTxs.map { tx =>
        GenesisTransactionSettings(tx.recipient.stringRepr, tx.amount)
      },
      genesis.header.baseTarget,
      settings.averageBlockDelay
    )
  }

  def calcInitialBaseTarget(): Long = {
    val posCalculator: PoSCalculator =
      if (settings.preActivated(BlockchainFeatures.FairPoS))
        if (settings.preActivated(BlockchainFeatures.BlockV5)) FairPoSCalculator.fromSettings(settings.functionalitySettings)
        else FairPoSCalculator.V1
      else NxtPoSCalculator

    val hitSource = ByteStr(Array.fill(crypto.DigestLength)(0: Byte))

    def getHit(account: KeyPair): BigInt = {
      val gs = if (settings.preActivated(BlockchainFeatures.BlockV5)) {
        val vrfProof = crypto.signVRF(account.privateKey, hitSource.arr)
        crypto.verifyVRF(vrfProof, hitSource.arr, account.publicKey).map(_.arr).explicitGet()
      } else generationSignature(hitSource, account.publicKey)

      hit(gs)
    }

    val (bt, delay, account, balance) =
      shares
        .filter(_._1.miner)
        .map {
          case (accountInfo, amount) =>
            def calc(delay: FiniteDuration) = posCalculator.calculateInitialBaseTarget(getHit(accountInfo.account), delay.toMillis, amount)

            @tailrec
            def search(delay: FiniteDuration): Long = {
              val bt = calc(delay)
              if (bt > 0) bt else search(delay + 10.millis)
            }

            val calculatedBT = calc(settings.averageBlockDelay)
            val initialBT =
              if (calculatedBT > 0) calculatedBT
              else search(settings.averageBlockDelay + 10.millis)

            val calcDelay = posCalculator.calculateDelay(getHit(accountInfo.account), initialBT, amount)
            (initialBT, calcDelay, accountInfo.account, amount)
        }
        .filter(_._2 >= settings.averageBlockDelay.toMillis)
        .minBy(_._2 - settings.averageBlockDelay.toMillis)

    //noinspection ScalaStyle
    println(s"First generated block timestamp: ${timestamp + posCalculator.calculateDelay(getHit(account), bt, balance)}, delay: $delay")
    bt
  }
}

case class InitialBlockchain(hitSource: ByteStr, settings: BlockchainSettings) extends Blockchain {
  def height: Int                                                                              = 1
  def score: BigInt                                                                            = 0
  def blockHeader(height: Int): Option[SignedBlockHeader]                                      = None
  def hitSource(height: Int): Option[ByteStr]                                                  = Some(hitSource)
  def carryFee: Long                                                                           = 0
  def heightOf(blockId: ByteStr): Option[Int]                                                  = None
  def approvedFeatures: Map[Short, Int]                                                        = Map()
  def activatedFeatures: Map[Short, Int]                                                       = settings.functionalitySettings.preActivatedFeatures
  def featureVotes(height: Int): Map[Short, Int]                                               = Map()
  def blockReward(height: Int): Option[Long]                                                   = None
  def blockRewardVotes(height: Int): Seq[Long]                                                 = Seq()
  def wavesAmount(height: Int): BigInt                                                         = 0
  def transferById(id: ByteStr): Option[(Int, TransferTransaction)]                            = None
  def transactionInfo(id: ByteStr): Option[(Int, Transaction, Boolean)]                        = None
  def transactionMeta(id: ByteStr): Option[(Int, Boolean)]                                     = None
  def containsTransaction(tx: Transaction): Boolean                                            = false
  def assetDescription(id: Asset.IssuedAsset): Option[AssetDescription]                        = None
  def resolveAlias(a: Alias): Either[ValidationError, Address]                                 = Left(GenericError("Empty blockchain"))
  def leaseDetails(leaseId: ByteStr): Option[LeaseDetails]                                     = None
  def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee                                       = VolumeAndFee.empty
  def balanceAtHeight(address: Address, height: Int, assetId: Asset): Option[(Int, Long)]      = None
  def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot] = Seq()
  def accountScript(address: Address): Option[AccountScriptInfo]                               = None
  def hasAccountScript(address: Address): Boolean                                              = false
  def assetScript(id: Asset.IssuedAsset): Option[AssetScriptInfo]                              = None
  def accountData(acc: Address, key: String): Option[DataEntry[_]]                             = None
  def leaseBalance(address: Address): LeaseBalance                                             = LeaseBalance.empty
  def balance(address: Address, mayBeAssetId: Asset): Long                                     = 0
}
