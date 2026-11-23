package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File
import com.digitalasset.canton.ConsoleScriptRunner
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.topology.store.StoredTopologyTransaction.GenericStoredTopologyTransaction
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.{
  DecentralizedNamespaceDefinition,
  MediatorSynchronizerState,
  PartyToParticipant,
  SequencerSynchronizerState,
  SynchronizerTrustCertificate,
  TopologyMapping,
}
import com.google.cloud.storage.Blob
import io.circe.Json
import io.circe.parser.parse
import org.lfdecentralizedtrust.splice.config.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.GcpBucket
import org.slf4j.event.Level

import java.nio.charset.StandardCharsets
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class PeriodicTopologySnapshotIntegrationTest[T <: BackupDumpConfig]
    extends IntegrationTestWithIsolatedEnvironment {

  private val runPrefix = s"periodic-topology-snapshot-test/${UUID.randomUUID()}"

  private val bucket = new GcpBucket(topologySnapshotLocation.bucket, loggerFactory)

  private val topologySnapshotInterval: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)

  private def topologySnapshotConfig: PeriodicBackupDumpConfig =
    PeriodicBackupDumpConfig(topologySnapshotLocation, topologySnapshotInterval)

  private def topologySnapshotLocation: BackupDumpConfig.Gcp =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting(TopologySnapshotTest), Some(runPrefix))

  private def listDump(utcDate: String): Seq[Blob] =
    bucket.listBlobsByPrefix(prefix = s"$runPrefix/topology_snapshot_$utcDate")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransformsToFront((_, conf) =>
        ConfigTransforms.updateAllSvAppConfigs((_, c) =>
          c.copy(topologySnapshotConfig = Some(topologySnapshotConfig))
        )(conf)
      )
      .withManualStart

  "sv1" should {
    "produces a topology snapshot in the background" in { implicit env =>
      val utcDate = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate.toString
      clue("topology snapshot is being produced")(
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          timeUntilSuccess = 40.seconds,
          within = {
            initDsoWithSv1Only()
          },
          assertion = logEntries => {
            forAtLeast(
              1,
              logEntries,
            )(logEntry =>
              logEntry.message should include(s"Took a new topology snapshot on $utcDate")
            )
          },
        )
      )

      clue("the 3 files created from the topology snapshot exist in gcp.")({
        val dumps = listDump(utcDate).filter(_.getSize > 0L)
        dumps should have size 3
        new String(
          dumps
            .find(_.getName.endsWith("metadata"))
            .getOrElse(throw new RuntimeException("Metadata file not found."))
            .getContent(),
          StandardCharsets.UTF_8,
        ) should include("SEQ::sv1") // the sequencerId change through ci runs
        forExactly(1, dumps) { dump => dump.getName should include("authorized") }
        forExactly(1, dumps) { dump => dump.getName should include("onboarding-state") }
      })

      clue("the topology snapshot decodes to JSONL with scripts/topology-snapshot-to-jsonl.sc")({
        val snapshot = listDump(utcDate)
          .find(_.getName.endsWith("onboarding-state.zst"))
          .getOrElse(fail("onboarding-state.zst not found"))
        checkDecodedSnapshot(snapshot)
      })

      clue("the topology snapshot of this run is removed from gcp.")({
        val notDeleted = bucket.listBlobsByPrefix(prefix = runPrefix).filterNot(_.delete())
        notDeleted.map(_.getName) shouldBe empty
      })
    }
  }

  private val decoderScript = File("scripts/topology-snapshot-to-jsonl.sc")

  private def readJsonl(file: File): Seq[Json] =
    file.lineIterator
      .map(line => parse(line).fold(err => fail(s"Invalid JSON line: $err"), identity))
      .toSeq

  private def str(row: Json, field: String): String =
    row.hcursor
      .get[String](field)
      .fold(err => fail(s"No string field $field in $row: $err"), identity)

  /** Runs the decoder script in-process on the snapshot and checks that its output contains
    * topology transactions and sequencer members that must exist in this topology. The expected
    * transactions are looked up in sv1's participant's topology store; they are the first
    * transaction of their mapping, created while bootstrapping the synchronizer and the DSO, so
    * before the snapshot was taken.
    */
  private def checkDecodedSnapshot(snapshot: Blob)(implicit env: SpliceTestConsoleEnvironment) = {
    val dir = File.newTemporaryDirectory("topology-snapshot-to-jsonl")
    try {
      val input = dir / "onboarding-state.zst"
      val transactionsFile = dir / "transactions.jsonl"
      val membersFile = dir / "members.jsonl"
      snapshot.downloadTo(input.path)
      // the script reads its settings from system properties when run in-process
      val settings = Map(
        "SNAPSHOT_IN" -> input,
        "SNAPSHOT_OUT" -> transactionsFile,
        "SNAPSHOT_MEMBERS_OUT" -> membersFile,
        "SNAPSHOT_SCHEMA_OUT" -> dir / "transactions-schema.json",
        "SNAPSHOT_MEMBERS_SCHEMA_OUT" -> dir / "members-schema.json",
        "SNAPSHOT_HEADER_OUT" -> dir / "header.json",
      )
      try {
        settings.foreach { case (k, v) => sys.props(k) = v.pathAsString }
        ConsoleScriptRunner.run(env.environment, decoderScript.toJava, logger) shouldBe Right(())
      } finally settings.keys.foreach(sys.props.remove)

      val schemaFiles = Seq("transactions-schema.json", "members-schema.json")
      forAll(schemaFiles)(f => parse((dir / f).contentAsString).isRight shouldBe true)

      val transactions = readJsonl(transactionsFile)
      val members = readJsonl(membersFile)
      val txHashes = transactions.map(str(_, "tx_hash")).toSet
      val memberIds = members.map(str(_, "member")).toSet

      val sv1Participant = sv1Backend.participantClientWithAdminToken
      val sv1ParticipantId = sv1Participant.id
      val storedTxs = sv1Participant.topology.transactions
        .list(
          store = Synchronizer(decentralizedSynchronizerId),
          timeQuery = TimeQuery.Range(None, None),
          operation = None,
        )
        .result

      def firstOf[M <: TopologyMapping: ClassTag](
          description: String
      )(p: M => Boolean): (GenericStoredTopologyTransaction, M) =
        storedTxs
          .flatMap { tx =>
            tx.mapping match {
              case m: M if p(m) => Some((tx, m))
              case _ => None
            }
          }
          .minByOption(_._1.validFrom)
          .getOrElse(fail(s"No $description in sv1's participant's topology store"))

      def checkContains(description: String, tx: GenericStoredTopologyTransaction) =
        clue(s"the output contains the $description") {
          val hash = tx.transaction.transaction.hash.hash.toHexString
          txHashes should contain(hash)
          val row = transactions.find(str(_, "tx_hash") == hash).value
          str(row, "valid_from") shouldBe tx.validFrom.value.toInstant.toString
          str(row, "operation") shouldBe "Replace"
          row
        }

      val (trustCert, _) = firstOf[SynchronizerTrustCertificate]("sv1 trust certificate")(
        _.participantId == sv1ParticipantId
      )
      str(checkContains("sv1 trust certificate", trustCert), "uid") shouldBe
        sv1ParticipantId.uid.toProtoPrimitive

      val (dsoNamespace, _) = firstOf[DecentralizedNamespaceDefinition]("DSO namespace")(
        _.namespace == dsoParty.uid.namespace
      )
      str(checkContains("DSO decentralized namespace", dsoNamespace), "namespace") shouldBe
        dsoParty.uid.namespace.toProtoPrimitive

      val (dsoHosting, _) = firstOf[PartyToParticipant]("DSO party to participant")(
        _.partyId == dsoParty
      )
      val dsoHostingRow = checkContains("DSO party to participant", dsoHosting)
      str(dsoHostingRow, "mapping_code") shouldBe "party_to_participant"
      dsoHostingRow.hcursor
        .downField("mapping")
        .get[String]("party")
        .value shouldBe dsoParty.toProtoPrimitive

      val (sequencersTx, sequencers) =
        firstOf[SequencerSynchronizerState]("sequencer synchronizer state")(_ => true)
      checkContains("sequencer synchronizer state", sequencersTx)
      val (mediatorsTx, mediators) =
        firstOf[MediatorSynchronizerState]("mediator synchronizer state")(_ => true)
      checkContains("mediator synchronizer state", mediatorsTx)

      clue("the members file has sv1's participant, sequencer and mediator") {
        memberIds should contain(sv1ParticipantId.toProtoPrimitive)
        forAll(sequencers.active.forgetNE)(s => memberIds should contain(s.toProtoPrimitive))
        forAll(mediators.active.forgetNE)(m => memberIds should contain(m.toProtoPrimitive))
        val sv1ParticipantRow =
          members.find(str(_, "member") == sv1ParticipantId.toProtoPrimitive).value
        str(sv1ParticipantRow, "status") shouldBe "active"
      }
    } finally dir.delete()
  }
}
