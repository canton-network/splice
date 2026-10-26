package org.lfdecentralizedtrust.splice.integration.tests

import com.sun.net.httpserver.HttpServer
import io.circe.Json
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
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.collection.mutable
import scala.sys.process.ProcessLogger

class ScanPartyMonitorScriptIntegrationTest extends IntegrationTest with WalletTestUtil {

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

  private def reasons(matches: Seq[Json], party: String): Seq[(String, Option[String])] =
    for {
      m <- matches
      r <- m.hcursor.downField("parties").downField(party).focus.flatMap(_.asArray).toList.flatten
    } yield (
      r.hcursor.get[String]("reason").fold(e => fail(e), identity),
      r.hcursor.get[String]("choice").toOption,
    )

  "scan_party_monitor.py reports transactions in which watched parties are confirming parties" in {
    implicit env =>
      val dbDir: Path = Files.createTempDirectory("scan-party-monitor")
      val db = dbDir.resolve("monitor.sqlite").toString
      val slackBodies = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val slack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      slack.createContext(
        "/slack",
        exchange => {
          slackBodies.add(
            new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          )
          exchange.sendResponseHeaders(200, -1)
          exchange.close()
        },
      )
      slack.start()

      try {
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
        val alice = aliceParty.toProtoPrimitive
        val bob = bobParty.toProtoPrimitive
        aliceWalletClient.tap(100.0)
        // pays for bob's preapproval
        bobValidatorWalletClient.tap(10.0)
        createTransferPreapprovalEnsuringItExists(bobWalletClient, bobValidatorBackend)

        clue("Take an ACS snapshot containing bob's TransferPreapproval") {
          sv1ScanBackend.forceAcsSnapshotNow()
        }

        val allMatches = mutable.ListBuffer.empty[Json]
        val scanUrl = sv1ScanBackend.httpClientConfig.url.toString()
        val commonArgs = Seq(
          "--scan-url",
          scanUrl,
          "--db",
          db,
          "--acs-page-size",
          "10",
          "--acs-requests-per-second",
          "20",
          "--updates-requests-per-second",
          "20",
          "--exit-when-caught-up",
        )

        clue("An interrupted seed reports progress and stops") {
          val run = runScript(commonArgs ++ Seq("--party", alice, "--stop-after-acs-pages", "1"))
          run.stderr.exists(_.contains("ACS seed: 1 pages, 10 contracts stored")) shouldBe true
          run.stderr.exists(_.contains("Stopping after 1 ACS pages")) shouldBe true
          run.stdout shouldBe empty
        }

        clue("The seed resumes and the script catches up with scan") {
          val run = runScript(commonArgs ++ Seq("--party", alice))
          run.stderr.exists(_.contains("Resuming the seed")) shouldBe true
          run.stderr.exists(_.contains("ACS seed complete")) shouldBe true
          run.stderr.exists(_.contains("Caught up with scan")) shouldBe true
        }

        actAndCheck(
          "Alice sends amulet to bob through bob's TransferPreapproval",
          aliceWalletClient.transferPreapprovalSend(bobParty, 10.0, UUID.randomUUID.toString),
        )(
          "The script, now also watching bob, reports the transfer for both parties",
          _ => {
            // Every run resumes from where the previous one stopped, so collect the outputs
            // of all attempts.
            val run = runScript(
              commonArgs ++ Seq(
                "--party",
                alice,
                "--party",
                bob,
                "--slack-webhook-url",
                s"http://127.0.0.1:${slack.getAddress.getPort}/slack",
              )
            )
            allMatches ++= run.matches
            reasons(allMatches.toSeq, alice) should contain(
              ("acting", Some("TransferPreapproval_Send"))
            )
            // bob's TransferPreapproval was created before the ACS snapshot, so this checks
            // that the script uses the contracts from the seed for parties added later.
            reasons(allMatches.toSeq, bob) should contain(
              ("exercised_contract_signatory", Some("TransferPreapproval_Send"))
            )
          },
        )

        clue("The matching transaction was posted to Slack") {
          val bodies = slackBodies.toArray(Array.empty[String]).toSeq
          forExactly(1, bodies) { body =>
            body should (include(bob) and include("TransferPreapproval_Send"))
          }
        }
      } finally {
        slack.stop(0)
      }
  }
}
