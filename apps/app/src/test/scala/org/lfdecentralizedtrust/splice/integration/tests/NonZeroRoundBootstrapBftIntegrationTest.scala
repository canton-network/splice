package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.concurrent.Threading
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
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
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
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
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

  "non-firstSV scans can provide reward totals for the initial round" in { implicit env =>
    import definitions.GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsOk

    // Advance rounds so the reward pipeline runs for the initial round:
    // AdvanceOpenMiningRoundTrigger archives oldest round → creates
    // CalculateRewardsV2 → RewardComputationTrigger computes totals.
    advanceTimeForRewardAutomationToRunForCurrentRound
    actAndCheck(
      "Advance to next round opening",
      advanceRoundsToNextRoundOpening,
    )(
      "SV1's scan has reward totals for the initial round",
      _ =>
        sv1ScanBackend
          .getRewardAccountingActivityTotals(initialRound) shouldBe a[
          RewardAccountingActivityTotalsOk
        ],
    )

    // Add a dummy 5th SV with a fake scan URL. This increases BFT f
    // from 0 to 1, requiring 2 agreeing Ok responses.
    addDummySvWithFakeScanUrl()

    // SV2's scan should also have the data once the BFT fix lets it
    // read from SV1. Currently fails — will pass after implementing
    // randomSingleCall for the initial round's BFT reads.
    eventually() {
      sv2ScanBackend
        .getRewardAccountingActivityTotals(initialRound) shouldBe a[
        RewardAccountingActivityTotalsOk
      ]
    }
  }

  private def addDummySvWithFakeScanUrl()(implicit
      env: SpliceTestConsoleEnvironment
  ): com.digitalasset.canton.topology.PartyId = {
    val dsoInfo = sv1Backend.getDsoInfo()
    val svParty = dsoInfo.svParty
    val dsoParty = dsoInfo.dsoParty

    // Random suffix avoids collisions with stale parties from
    // previous runs (databases persist between local test runs).
    val dummySvParty = sv1Backend.participantClientWithAdminToken.ledger_api.parties
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
          svParty.toProtoPrimitive,
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

    // Pause ALL delegate-based triggers to prevent DsoRules contract
    // churn during the SetSynchronizerNodeConfig submission.
    // Sleep briefly after pausing to let in-flight commands complete.
    env.svs.local.foreach(
      _.dsoDelegateBasedAutomation.triggers[Trigger].foreach(_.pause().futureValue)
    )
    Threading.sleep(2000)
    clue("Set fake scan URL on dummy SV") {
      setDummySvScanUrl(dsoParty, dummySvParty)
    }
    env.svs.local.foreach(
      _.dsoDelegateBasedAutomation.triggers[Trigger].foreach(_.resume())
    )

    dummySvParty
  }

  private def setDummySvScanUrl(
      dsoParty: com.digitalasset.canton.topology.PartyId,
      dummySvParty: com.digitalasset.canton.topology.PartyId,
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

    eventuallySucceeds() {
      val info = sv1Backend.getDsoInfo()
      val currentDsoRulesCid = info.dsoRules.contractId
      val currentNodeStateCid = info.svNodeStates
        .getOrElse(dummySvParty, fail("SvNodeState not found for dummy SV"))
        .contractId
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitJava(
          actAs = Seq(dummySvParty),
          readAs = Seq(dsoParty),
          commands = currentDsoRulesCid
            .exerciseDsoRules_SetSynchronizerNodeConfig(
              dummySvParty.toProtoPrimitive,
              synchronizerId,
              nodeConfig,
              currentNodeStateCid,
            )
            .commands()
            .asScala
            .toSeq,
        )
    }
  }
}
