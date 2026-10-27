package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, entity, path, post}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.automation.{
  AcsSnapshotBackfillingTrigger,
  AcsSnapshotTrigger,
}
import org.lfdecentralizedtrust.splice.unit.http.HttpServerSupport
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.sys.process.ProcessLogger

class ScanPartyMonitorScriptIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with HttpServerSupport {

  // the fake Slack webhook only serves the script
  override def bindLocalhostOnly: Boolean = true

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // the test forces a snapshot, which would race with the automatic ones
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.withPausedTrigger[AcsSnapshotTrigger].withPausedTrigger[AcsSnapshotBackfillingTrigger]
        )(config)
      )

  private val script = "scripts/scan-party-monitor/scan_party_monitor.py"

  private case class ScriptRun(exitCode: Int, stdout: Seq[String], stderr: Seq[String]) {
    def matches: Seq[Json] = stdout.map(line =>
      io.circe.parser.parse(line).fold(e => fail(s"Invalid JSON line $line: $e"), identity)
    )
  }

  private def runScript(args: Seq[String]): ScriptRun = {
    val stdout = mutable.ListBuffer.empty[String]
    val stderr = mutable.ListBuffer.empty[String]
    val exitCode = scala.sys.process
      .Process(Seq("python", script) ++ args)
      .!(ProcessLogger(stdout.append(_), stderr.append(_)))
    logger.debug(s"$script ${args.mkString(" ")} exited with $exitCode:\n${stderr.mkString("\n")}")
    stderr.filter(_.contains(" ERROR ")) shouldBe empty withClue "ERROR lines"
    exitCode shouldBe 0 withClue s"exit code; stderr:\n${stderr.mkString("\n")}"
    ScriptRun(exitCode, stdout.toSeq, stderr.toSeq)
  }

  private def writeConfig(path: Path, config: Json): String = {
    Files.writeString(path, config.noSpaces)
    path.toString
  }

  private def reasonsOf(matches: Seq[Json], party: String): Seq[(String, Json)] =
    for {
      m <- matches
      updateId = m.hcursor.get[String]("update_id").fold(e => fail(e), identity)
      r <- m.hcursor.downField("parties").downField(party).focus.flatMap(_.asArray).toList.flatten
    } yield (updateId, r)

  private def reasons(matches: Seq[Json], party: String): Seq[String] =
    reasonsOf(matches, party).map { case (_, r) =>
      r.hcursor.get[String]("reason").fold(e => fail(e), identity)
    }

  /** (update id, receiver) of the transfer reasons of `party`. */
  private def transfers(matches: Seq[Json], party: String): Seq[(String, String)] =
    reasonsOf(matches, party).collect {
      case (updateId, r) if r.hcursor.get[String]("reason").contains("transfer") =>
        (updateId, r.hcursor.get[String]("receiver").fold(e => fail(e), identity))
    }

  "scan_party_monitor.py reports transfers to unexpected recipients and confirmations" in {
    implicit env =>
      val dir: Path = Files.createTempDirectory("scan-party-monitor")
      val db = dir.resolve("monitor.sqlite")
      import env.{actorSystem, executionContext}
      val slackBodies = new ConcurrentLinkedQueue[String]()
      // mock Slack webhook to capture the posted messages
      val slackRoute = path("slack") {
        post {
          entity(as[String]) { body =>
            slackBodies.add(body)
            complete(StatusCodes.OK)
          }
        }
      }

      withHttpServer(slackRoute) { slack =>
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
        val charlieParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
        val alice = aliceParty.toProtoPrimitive
        val bob = bobParty.toProtoPrimitive
        val charlie = charlieParty.toProtoPrimitive
        aliceWalletClient.tap(100.0)
        // a holding of bob from before the snapshot, which only a re-seed can store
        bobWalletClient.tap(50.0)
        // pay for the preapprovals
        bobValidatorWalletClient.tap(10.0)
        aliceValidatorWalletClient.tap(10.0)
        // these wait for scan to ingest the preapprovals, and hence the taps
        createTransferPreapprovalEnsuringItExists(bobWalletClient, bobValidatorBackend)
        createTransferPreapprovalEnsuringItExists(charlieWalletClient, aliceValidatorBackend)

        clue("Take an ACS snapshot containing alice's and bob's amulets") {
          sv1ScanBackend.forceAcsSnapshotNow()
        }

        val scanUrl = sv1ScanBackend.httpClientConfig.url.toString()
        val slackUrl = s"http://127.0.0.1:${slack.localAddress.getPort}/slack"
        def args(db: Path, config: String, extra: String*): Seq[String] = Seq(
          "--scan-url",
          scanUrl,
          "--db",
          db.toString,
          "--config",
          config,
          "--acs-page-size",
          "1",
          "--acs-requests-per-second",
          "20",
          "--events-requests-per-second",
          "20",
          "--exit-when-caught-up",
        ) ++ extra

        // alice may transfer to bob; charlie is tracked so that the seed has several pages
        val aliceToBobOnly = writeConfig(
          dir.resolve("alice-to-bob-only.json"),
          Json.obj(
            "parties" -> Json.obj(
              alice -> Json.obj("additional_recipients" -> Json.arr(Json.fromString(bob))),
              charlie -> Json.obj(),
            )
          ),
        )

        clue("An interrupted seed reports progress and stops") {
          val run = runScript(args(db, aliceToBobOnly, "--stop-after-acs-pages", "1"))
          run.stderr.exists(_.contains("ACS seed: 1 pages,")) shouldBe true
          run.stderr.exists(_.contains("Stopping after 1 ACS pages")) shouldBe true
          run.stdout shouldBe empty
        }

        clue("The seed resumes and the script catches up with scan") {
          val run = runScript(args(db, aliceToBobOnly))
          run.stderr.exists(_.contains("Resuming the seed")) shouldBe true
          run.stderr.exists(_.contains("ACS seed complete")) shouldBe true
          run.stderr.exists(_.contains("Caught up with scan")) shouldBe true
          run.stdout shouldBe empty
        }

        val dbBeforeTransfers = dir.resolve("before-transfers.sqlite")
        Files.copy(db, dbBeforeTransfers)

        val transferMatches = mutable.ListBuffer.empty[Json]
        val (_, charlieTransfers) = actAndCheck(
          "Alice sends amulet to bob once and to charlie twice", {
            aliceWalletClient.transferPreapprovalSend(bobParty, 10.0, UUID.randomUUID.toString)
            aliceWalletClient.transferPreapprovalSend(charlieParty, 5.0, UUID.randomUUID.toString)
            aliceWalletClient.transferPreapprovalSend(charlieParty, 6.0, UUID.randomUUID.toString)
          },
        )(
          "The script reports both transfers to charlie",
          _ => {
            // Every run resumes from where the previous one stopped, so collect the outputs
            // of all attempts.
            transferMatches ++= runScript(args(db, aliceToBobOnly)).matches
            val charlieTransfers = transfers(transferMatches.toSeq, alice)
            charlieTransfers.map(_._2) shouldBe Seq(charlie, charlie)
            charlieTransfers
          },
        )

        clue("Only the transfers to charlie were reported") {
          // the transfer to bob came first, so it has been processed
          reasons(transferMatches.toSeq, alice).toSet shouldBe Set("transfer")
          reasons(transferMatches.toSeq, bob) shouldBe empty
          reasons(transferMatches.toSeq, charlie) shouldBe empty
        }

        clue("Catching up on several alerts posts one Slack message") {
          runScript(args(dbBeforeTransfers, aliceToBobOnly, "--slack-webhook-url", slackUrl))
          val bodies = slackBodies.asScala.toSeq
          inside(bodies) { case Seq(body) =>
            forAll(charlieTransfers) { case (updateId, _) => body should include(updateId) }
          }
        }

        // bob becomes a common expected recipient and is newly tracked for all confirmations
        val trackBob = writeConfig(
          dir.resolve("track-bob.json"),
          Json.obj(
            "expected_recipients" -> Json.arr(Json.fromString(bob)),
            "parties" -> Json.obj(
              alice -> Json.obj(),
              charlie -> Json.obj(),
              bob -> Json.obj("alert_confirmations" -> Json.True),
            ),
          ),
        )

        clue("Adding bob re-seeds, reports bob's confirmations, and doesn't repeat alice's") {
          val slackBodiesBefore = slackBodies.size()
          val run = runScript(args(db, trackBob, "--slack-webhook-url", slackUrl))
          run.stderr.exists(_.contains("re-seeding")) shouldBe true
          // through his preapproval, bob confirms alice's transfer to him
          reasons(run.matches, bob) should contain("confirming")
          reasons(run.matches, alice) shouldBe empty
          val newBodies = slackBodies.asScala.toSeq.drop(slackBodiesBefore)
          inside(newBodies) { case Seq(body) =>
            body should (include(bob) and include("confirming party"))
          }
        }

        val bobMatches = mutable.ListBuffer.empty[Json]
        actAndCheck(
          "Bob sends amulet to charlie",
          bobWalletClient.transferPreapprovalSend(charlieParty, 1.0, UUID.randomUUID.toString),
        )(
          "The script reports bob's transfer, from holdings that the re-seed stored",
          _ => {
            bobMatches ++= runScript(args(db, trackBob)).matches
            transfers(bobMatches.toSeq, bob).map(_._2) shouldBe Seq(charlie)
          },
        )

        val aliceConfirmations = writeConfig(
          dir.resolve("alice-confirmations.json"),
          Json.obj(
            "expected_recipients" -> Json.arr(Json.fromString(bob)),
            "parties" -> Json.obj(
              alice -> Json.obj("alert_confirmations" -> Json.True),
              charlie -> Json.obj(),
              bob -> Json.obj("alert_confirmations" -> Json.True),
            ),
          ),
        )
        val privateMatches = mutable.ListBuffer.empty[Json]
        actAndCheck(
          "Alice offers bob a transfer, which the DSO party does not see",
          aliceWalletClient.createTransferOffer(
            bobParty,
            2.0,
            "private offer",
            CantonTimestamp.now().plus(Duration.ofMinutes(10)),
            UUID.randomUUID.toString,
          ),
        )(
          "The script reports alice confirming the private transaction",
          _ => {
            privateMatches ++= runScript(args(db, aliceConfirmations)).matches
            val privateOnes =
              privateMatches.toSeq.filter(_.hcursor.get[Boolean]("private").contains(true))
            reasons(privateOnes, alice) should contain("confirming")
          },
        )
      }
  }
}
