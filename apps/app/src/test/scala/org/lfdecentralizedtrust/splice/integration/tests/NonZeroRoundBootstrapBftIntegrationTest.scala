package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.{
  CometBftConfig,
  CometBftNodeConfig,
  GovernanceKeyConfig,
  SequencingKeyConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  ScanConfig,
  SynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_AddSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_AddSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.scalatest.concurrent.PatienceConfiguration
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}

import java.util.Optional
import scala.jdk.CollectionConverters.*

/** Tests that non-firstSV scans can provide reward totals for the
  * initial round when bootstrapping TBAR at a non-zero round with
  * BFT f >= 1.
  *
  * Adds a dummy 5th SV to increase the BFT quorum from 1 to 2.
  * Currently fails because only SV1's scan has the initial round's
  * data — will pass after implementing randomSingleCall for the
  * initial round's BFT reads.
  *
  * Related: issue #6110
  */
class NonZeroRoundBootstrapBftIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  private val initialRound = 4815L

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .addConfigTransform((_, config) =>
        ConfigTransforms.withRewardConfig(
          InitialRewardConfig(
            mintingVersion = "RewardVersion_TrafficBasedAppRewards",
            dryRunVersion = None,
            appRewardCouponThreshold = BigDecimal("0"),
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialRound = initialRound)
        )(config)
      )
      .addConfigTransform((_, config) => ConfigTransforms.withNoVoteCooldown(config))
      // Short tick duration so rounds advance quickly in real time,
      // allowing CalculateRewardsV2 contracts to be created.
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )

  "non-firstSV scans can provide reward totals for the initial round" in { implicit env =>
    // Add a dummy 5th SV with a fake scan URL. This increases BFT f
    // from 0 to 1, requiring 2 agreeing Ok responses. Since only
    // SV1's scan has the initial round data, BFT reads from SV2/3/4
    // can't reach quorum.
    addDummySvWithFakeScanUrl()

    import definitions.GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsOk

    import scala.concurrent.duration.DurationInt

    // SV1's scan has the data (firstSV seeding). Wait up to 60s because
    // in real time the reward pipeline (verdict ingestion → activity
    // completeness → reward computation) needs multiple trigger cycles.
    eventually(timeUntilSuccess = 60.seconds) {
      sv1ScanBackend
        .getRewardAccountingActivityTotals(initialRound) shouldBe a[RewardAccountingActivityTotalsOk]
    }

    // SV2's scan should also have the data once the BFT fix lets it
    // read from SV1. Currently fails — will pass after implementing
    // randomSingleCall for the initial round's BFT reads.
    eventually(timeUntilSuccess = 60.seconds) {
      sv2ScanBackend
        .getRewardAccountingActivityTotals(initialRound) shouldBe a[RewardAccountingActivityTotalsOk]
    }
  }

  private def addDummySvWithFakeScanUrl()(implicit
      env: SpliceTestConsoleEnvironment
  ): com.digitalasset.canton.topology.PartyId = {
    val dsoParty = sv1Backend.getDsoInfo().svParty

    // Random suffix avoids collisions with stale parties from
    // previous runs (databases persist between local test runs).
    val dummySvParty = sv1Backend.participantClientWithAdminToken
      .ledger_api.parties
      .allocate(s"dummy-sv5-${scala.util.Random.nextInt().toHexString}")
      .party

    val addSvAction = new ARC_DsoRules(
      new SRARC_AddSv(
        new DsoRules_AddSv(
          dummySvParty.toProtoPrimitive,
          "Dummy-SV5",
          1000L,
          "dummy-participant-id",
          new Round(initialRound),
        )
      )
    )

    val (_, voteRequest) = actAndCheck(
      "sv1 creates vote request to add dummy SV5",
      eventuallySucceeds() {
        sv1Backend.createVoteRequest(
          dsoParty.toProtoPrimitive,
          addSvAction,
          "url",
          "Add dummy SV5 for BFT threshold test",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        )
      },
    )(
      "vote request exists",
      _ => sv1Backend.listVoteRequests().loneElement,
    )

    actAndCheck(
      "sv2 and sv3 vote yes (3 votes total → executes)", {
        Seq(sv2Backend, sv3Backend).foreach { sv =>
          eventuallySucceeds() {
            sv.castVote(voteRequest.contractId, true, "url", "description")
          }
        }
      },
    )(
      "dummy SV5 is in DsoRules",
      _ => {
        val svs = sv1Backend.getDsoInfo().dsoRules.payload.svs
        svs.asScala should contain key dummySvParty.toProtoPrimitive
      },
    )

    clue("Set fake scan URL on dummy SV") {
      setDummySvScanUrl(dummySvParty)
    }

    dummySvParty
  }

  private def setDummySvScanUrl(
      dummySvParty: com.digitalasset.canton.topology.PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val synchronizerId = decentralizedSynchronizerId.toProtoPrimitive

    val nodeConfig = new SynchronizerNodeConfig(
      new CometBftConfig(
        Map.empty[String, CometBftNodeConfig].asJava,
        Seq.empty[GovernanceKeyConfig].asJava,
        Seq.empty[SequencingKeyConfig].asJava,
      ),
      Optional.empty(), // sequencer
      Optional.empty(), // mediator
      // Unreachable URL — BFT marks it as a failed peer, increasing
      // totalNumber (and thus f) without needing a running scan.
      Optional.of(new ScanConfig("http://localhost:1")),
      Optional.empty(), // legacySequencerConfig
      Optional.empty(), // sequencerIdentity
      Optional.empty(), // physicalSynchronizers
    )

    // SV1's ledger API user needs actAs rights for the dummy party to
    // submit SetSynchronizerNodeConfig (controller = sv party) via
    // SV1's SpliceLedgerConnection.
    sv1Backend.participantClientWithAdminToken.ledger_api.users.rights
      .grant(sv1Backend.config.ledgerApiUser, actAs = Set(dummySvParty))

    // Use SV1's SpliceLedgerConnection (not raw submitJava) because it
    // has built-in retry for CONTRACT_NOT_FOUND caused by concurrent
    // DsoRules churn from SV automation.
    // See ValidatorSequencerConnectionIntegrationTest for the same pattern.

    val dsoStore = sv1Backend.appState.dsoStore
    val connection =
      sv1Backend.appState.svAutomation.connection(SpliceLedgerConnectionPriority.Low)
    import scala.concurrent.duration.DurationInt
    (for {
      rulesAndState <- dsoStore.getDsoRulesWithSvNodeState(dummySvParty)
      cmd = rulesAndState.dsoRules.exercise(
        _.exerciseDsoRules_SetSynchronizerNodeConfig(
          dummySvParty.toProtoPrimitive,
          synchronizerId,
          nodeConfig,
          rulesAndState.svNodeState.contractId,
        )
      )
      _ <- connection
        .submit(Seq(dummySvParty), Seq(dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield ()).futureValue(timeout = PatienceConfiguration.Timeout(60.seconds))
  }
}
