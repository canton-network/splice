package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.NonEmptyList
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.data.GrpcSequencerConnection
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{ParticipantClientReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeConfigClient
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, Tags, WalletTestUtil}

import scala.jdk.OptionConverters.*
import scala.concurrent.Future
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority.Low
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  MediatorConfig,
  PhysicalSynchronizerNodeConfig,
  SequencerConnectionConfig,
  SequencerIdentityConfig,
  SynchronizerNodeConfig,
}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.validator.config.ValidatorTrustedSynchronizerConfig
import org.slf4j.event.Level

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_24
class ValidatorSequencerConnectionIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with SvTestUtil
    with WalletTestUtil {

  private val globalSyncAlias = SynchronizerAlias.tryCreate("global")
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            c.copy(
              domains = c.domains.copy(
                global = c.domains.global.copy(
                  trustedSynchronizerConfig = Some(
                    ValidatorTrustedSynchronizerConfig(
                      svNames = NonEmptyList.of(getSvName(1), getSvName(2), getSvName(3)),
                      threshold = 2,
                    )
                  )
                )
              ),
              automation =
                c.automation.copy(pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)),
            )
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers" in {
    implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        sv2Backend,
        sv2ScanBackend,
        sv3Backend,
        sv3ScanBackend,
        sv4Backend,
        sv4ScanBackend,
      )

      // We must wait for the sequencers to "mature" in Scan before starting the validator.
      // WITHOUT THIS: Alice queries Scan and finds that although 4 sequencers are listed,
      // some (like SV3) have an 'availableAfter' timestamp in the future.
      // Alice's DomainConnector correctly ignores these "not yet available" sequencers.
      // If Alice only finds 1 valid sequencer but is configured with a trust threshold of 2,
      // her validator app will crash on startup to prevent operating with insufficient trust.
      waitForAllSequencersAvailable()

      aliceValidatorBackend.startSync()

      val sv1Url = getPublicSequencerUrl(sv1Backend)
      val sv2InitialUrl = getPublicSequencerUrl(sv2Backend)
      val sv3Url = getPublicSequencerUrl(sv3Backend)
      val initialExpectedUrls = Set(sv1Url, sv2InitialUrl, sv3Url)

      withClue("Validator should connect to the filtered list of sequencers from the config") {
        eventually(120.seconds, 1.second) {
          val connectedUrls = getSequencerPublicUrls(
            aliceValidatorBackend.participantClientWithAdminToken,
            globalSyncAlias,
          )
          connectedUrls shouldBe initialExpectedUrls

          val currentThreshold = getSequencerTrustThreshold(
            aliceValidatorBackend.participantClientWithAdminToken,
            globalSyncAlias,
          )
          currentThreshold shouldBe 2
        }
      }
      val newSv2Address = "localhost:19108"
      val newSv2Url = "http://" + newSv2Address

      setSequencerUrl(sv2Backend, newSv2Url).futureValue

      val updatedExpectedUrls = Set(sv1Url, newSv2Address, sv3Url)

      withClue("Validator should eventually see the updated sequencer URL from the ledger") {
        eventually(120.seconds, 1.second) {
          val connectedUrls = getSequencerPublicUrls(
            aliceValidatorBackend.participantClientWithAdminToken,
            globalSyncAlias,
          )
          connectedUrls shouldBe updatedExpectedUrls
        }
      }

      withClue("Connection aliases should be sequencer IDs, not SV names") {
        val aliases = getSequencerAliases(
          aliceValidatorBackend.participantClientWithAdminToken,
          globalSyncAlias,
        )
        all(aliases) should startWith("SEQ::")
      }

      withClue("Alice's validator should remain functional after the URL change") {
        eventuallySucceeds() {
          aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
        }
      }

  }

  "scan does not serve a sequencer id claimed by multiple SVs and validators connect to the remaining sequencers" in {
    implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        sv2Backend,
        sv2ScanBackend,
        sv3Backend,
        sv3ScanBackend,
        sv4Backend,
        sv4ScanBackend,
      )
      // From splice-dso-governance 0.1.32 on, DsoRules rejects a sequencer id claimed by another SV,
      // so duplicate claims can only be set up when an older version is vetted.
      val duplicateClaimsRejected = sv4Backend.appState.dsoAutomation.packageVersionSupport
        .supportsSynchronizerNodeIdClaimsCheck(Seq(dsoParty), env.environment.clock.now)
        .futureValue
        .supported
      if (duplicateClaimsRejected) {
        logger.info("Skipping: DsoRules rejects duplicate sequencer id claims")
      } else {
        waitForAllSequencersAvailable()

        val initialSequencers = sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers)
        val sv2SequencerId = initialSequencers.find(_.svName == getSvName(2)).value.id
        val sv4SequencerId = initialSequencers.find(_.svName == getSvName(4)).value.id

        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          {
            // sv4 claims sv2's sequencer id while publishing its own url
            setSequencerId(sv4Backend, sv2SequencerId.toProtoPrimitive).futureValue

            withClue("Scan should serve neither of the entries claiming sv2's sequencer id") {
              eventually() {
                val servedSequencers = sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers)
                servedSequencers.map(_.svName).toSet shouldBe Set(getSvName(1), getSvName(3))
                servedSequencers.map(_.id) should not contain sv2SequencerId
              }
            }

            bobValidatorBackend.startSync()

            withClue("Validator should connect to the sequencers with a unique id") {
              eventually(120.seconds, 1.second) {
                getSequencerPublicUrls(
                  bobValidatorBackend.participantClientWithAdminToken,
                  globalSyncAlias,
                ) shouldBe Set(getPublicSequencerUrl(sv1Backend), getPublicSequencerUrl(sv3Backend))
              }
            }

            withClue("Restoring sv4's sequencer id makes scan serve all sequencers again") {
              setSequencerId(sv4Backend, sv4SequencerId.toProtoPrimitive).futureValue
              eventually() {
                forAll(Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend)) {
                  scanBackend =>
                    scanBackend
                      .listDsoSequencers()
                      .flatMap(_.sequencers)
                      .map(_.svName)
                      .toSet shouldBe
                      Set(getSvName(1), getSvName(2), getSvName(3), getSvName(4))
                }
              }
            }
          },
          entries => {
            entries should not be empty
            forAll(entries)(_.warningMessage should include("as it is claimed by multiple SVs"))
          },
        )

        withClue("Validator should reconcile to all sequencers once the duplicate is gone") {
          eventually(120.seconds, 1.second) {
            getSequencerPublicUrls(
              bobValidatorBackend.participantClientWithAdminToken,
              globalSyncAlias,
            ) shouldBe Set(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(
              getPublicSequencerUrl
            )
          }
        }
      }
  }

  "an SV cannot publish the sequencer or mediator id of another SV" taggedAs Tags.SpliceDsoGovernance_0_1_32 in {
    implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        sv2Backend,
        sv2ScanBackend,
        sv3Backend,
        sv3ScanBackend,
        sv4Backend,
        sv4ScanBackend,
      )

      withClue("The vetted splice-dso-governance version checks node id claims") {
        sv2Backend.appState.dsoAutomation.packageVersionSupport
          .supportsSynchronizerNodeIdClaimsCheck(
            Seq(dsoParty),
            env.environment.clock.now,
          )
          .futureValue
          .supported shouldBe true
      }

      val (sv1Config, sv2Config) = eventually() {
        val sv1Config = globalSynchronizerNodeConfig(sv1Backend)
        val sv2Config = globalSynchronizerNodeConfig(sv2Backend)
        sv1Config.sequencerIdentity.toScala should not be empty
        sv1Config.mediator.toScala should not be empty
        sv2Config.sequencerIdentity.toScala should not be empty
        sv2Config.mediator.toScala should not be empty
        (sv1Config, sv2Config)
      }
      val sv1SequencerId = sv1Config.sequencerIdentity.get().sequencerId
      val sv1MediatorId = sv1Config.mediator.get().mediatorId

      def assertRejected(
          update: SynchronizerNodeConfig => SynchronizerNodeConfig,
          expectedError: String,
      ) =
        loggerFactory.suppressWarningsAndErrors {
          updateSynchronizerNodeConfig(sv2Backend)(
            update
          ).failed.futureValue.getMessage should include(
            expectedError
          )
        }

      clue("SV2 cannot publish the sequencer id of SV1") {
        assertRejected(
          withSequencerId(_, sv1SequencerId),
          "Sequencer id is not claimed by another SV",
        )
      }
      clue("SV2 cannot publish the mediator id of SV1") {
        assertRejected(
          withMediatorId(_, sv1MediatorId),
          "Mediator id is not claimed by another SV",
        )
      }

      clue("The published node ids are unchanged") {
        globalSynchronizerNodeConfig(
          sv1Backend
        ).sequencerIdentity shouldBe sv1Config.sequencerIdentity
        globalSynchronizerNodeConfig(sv1Backend).mediator shouldBe sv1Config.mediator
        globalSynchronizerNodeConfig(
          sv2Backend
        ).sequencerIdentity shouldBe sv2Config.sequencerIdentity
        globalSynchronizerNodeConfig(sv2Backend).mediator shouldBe sv2Config.mediator
        val publishedSequencerIds =
          sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers).map(_.id)
        publishedSequencerIds.distinct should have size publishedSequencerIds.size.toLong
      }

      clue("SV2 can still update its own config") {
        val newSv2Url = "http://localhost:19108"
        updateSynchronizerNodeConfig(sv2Backend)(sv2CurrentConfig =>
          new SynchronizerNodeConfig(
            sv2CurrentConfig.cometBft,
            sv2CurrentConfig.sequencer,
            sv2CurrentConfig.mediator,
            sv2CurrentConfig.scan,
            sv2CurrentConfig.legacySequencerConfig,
            sv2CurrentConfig.sequencerIdentity,
            sv2CurrentConfig.physicalSynchronizers.map(
              _.asScala.view
                .mapValues(_ =>
                  new PhysicalSynchronizerNodeConfig(
                    Some(new SequencerConnectionConfig(newSv2Url)).toJava
                  )
                )
                .toMap
                .asJava
            ),
          )
        ).futureValue
        eventually() {
          sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers).map(_.url) should contain(
            newSv2Url
          )
        }
      }
  }

  private def waitForAllSequencersAvailable()(implicit env: SpliceTestConsoleEnvironment): Unit =
    withClue("Wait for all 4 SV sequencers to be available and valid in Scan") {
      eventually(60.seconds, 1.second) {
        val allSequencers = sv1ScanBackend.listDsoSequencers()
        val now = env.environment.clock.now
        val availableSequencers = for {
          domain <- allSequencers
          sequencer <- domain.sequencers
          if sequencer.url.nonEmpty && !now.toInstant.isBefore(sequencer.availableAfter)
        } yield sequencer
        availableSequencers.size shouldBe 4
      }
    }

  private def getPublicSequencerUrl(sv: SvAppBackendReference): String = {
    val fullUrl = sv.config.localSynchronizerNodes.current.sequencer.externalPublicApiUrl
    Uri(fullUrl).authority.toString()
  }

  private def getSequencerTrustThreshold(
      participantConnection: ParticipantClientReference,
      synchronizerAlias: SynchronizerAlias,
  ): Int = {
    participantConnection.synchronizers
      .config(synchronizerAlias)
      .value
      .sequencerConnections
      .sequencerTrustThreshold
      .unwrap
  }

  private def getSequencerPublicUrls(
      participantConnection: ParticipantClientReference,
      synchronizerAlias: SynchronizerAlias,
  ): Set[String] = {
    val sequencerConnections = participantConnection.synchronizers
      .config(synchronizerAlias)
      .value
      .sequencerConnections

    sequencerConnections.connections.forgetNE.collect {
      case GrpcSequencerConnection(endpoints, _, _, _, _) => endpoints.head1.toString
    }.toSet
  }

  private def getSequencerAliases(
      participantConnection: ParticipantClientReference,
      synchronizerAlias: SynchronizerAlias,
  ): Seq[String] = {
    val sequencerConnections = participantConnection.synchronizers
      .config(synchronizerAlias)
      .value
      .sequencerConnections

    sequencerConnections.connections.forgetNE.collect {
      case GrpcSequencerConnection(_, _, _, alias, _) => alias.unwrap
    }
  }

  private def setSequencerUrl(
      svBackend: SvAppBackendReference,
      newUrl: String,
  )(implicit env: SpliceTestConsoleEnvironment): Future[Unit] =
    updateSynchronizerNodeConfig(svBackend) { synchronizerNodeConfig =>
      new SynchronizerNodeConfig(
        synchronizerNodeConfig.cometBft,
        None.toJava,
        synchronizerNodeConfig.mediator,
        synchronizerNodeConfig.scan,
        None.toJava,
        synchronizerNodeConfig.sequencerIdentity,
        synchronizerNodeConfig.physicalSynchronizers.toScala
          .map(
            _.asScala
              .map { case (serial, _) =>
                serial -> new PhysicalSynchronizerNodeConfig(
                  Some(
                    new SequencerConnectionConfig(
                      newUrl
                    )
                  ).toJava
                )
              }
              .toMap
              .asJava
          )
          .toJava,
      )
    }

  private def setSequencerId(
      svBackend: SvAppBackendReference,
      sequencerId: String,
  )(implicit env: SpliceTestConsoleEnvironment): Future[Unit] =
    updateSynchronizerNodeConfig(svBackend) { synchronizerNodeConfig =>
      new SynchronizerNodeConfig(
        synchronizerNodeConfig.cometBft,
        synchronizerNodeConfig.sequencer,
        synchronizerNodeConfig.mediator,
        synchronizerNodeConfig.scan,
        synchronizerNodeConfig.legacySequencerConfig,
        synchronizerNodeConfig.sequencerIdentity.toScala
          .map(identity => new SequencerIdentityConfig(sequencerId, identity.availableAfter))
          .toJava,
        synchronizerNodeConfig.physicalSynchronizers,
      )
    }

  private def updateSynchronizerNodeConfig(
      svBackend: SvAppBackendReference
  )(update: SynchronizerNodeConfig => SynchronizerNodeConfig)(implicit
      env: SpliceTestConsoleEnvironment
  ): Future[Unit] = {

    val appState = svBackend.appState

    val dsoStore = appState.dsoStore
    val svStore = appState.svStore
    val svParty = svStore.key.svParty
    val dsoParty = svStore.key.dsoParty
    val svAutomation = appState.svAutomation
    val connection = svAutomation.connection(Low)
    val participantAdmin = appState.participantAdminConnection
    val synchronizerAlias = svBackend.config.domains.global.alias

    for {
      synchronizerId <- participantAdmin.getSynchronizerId(synchronizerAlias)
      rulesAndState <- dsoStore.getDsoRulesWithSvNodeState(svParty)
      nodeState = rulesAndState.svNodeState.payload

      synchronizerNodeConfig = nodeState.state.synchronizerNodes.asScala.getOrElse(
        synchronizerId.toProtoPrimitive,
        sys.error(s"No config found for synchronizer $synchronizerId"),
      )

      otherSvNodeStateCids <- SynchronizerNodeConfigClient.otherSvNodeStateCids(
        dsoStore,
        svParty,
        appState.dsoAutomation.packageVersionSupport,
        env.environment.clock.now,
      )
      cmd = rulesAndState.dsoRules.exercise(
        _.exerciseDsoRules_SetSynchronizerNodeConfig(
          svParty.toProtoPrimitive,
          synchronizerId.toProtoPrimitive,
          update(synchronizerNodeConfig),
          rulesAndState.svNodeState.contractId,
          otherSvNodeStateCids,
        )
      )
      _ <- connection
        .submit(Seq(svParty), Seq(dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield ()
  }

  private def globalSynchronizerNodeConfig(
      svBackend: SvAppBackendReference
  )(implicit env: SpliceTestConsoleEnvironment): SynchronizerNodeConfig = {
    val dsoInfo = svBackend.getDsoInfo()
    dsoInfo
      .svNodeStates(dsoInfo.svParty)
      .payload
      .state
      .synchronizerNodes
      .asScala
      .getOrElse(
        decentralizedSynchronizerId.toProtoPrimitive,
        fail(s"No node config for the global synchronizer published by ${dsoInfo.svParty}"),
      )
  }

  private def withSequencerId(config: SynchronizerNodeConfig, sequencerId: String) =
    new SynchronizerNodeConfig(
      config.cometBft,
      config.sequencer,
      config.mediator,
      config.scan,
      config.legacySequencerConfig,
      config.sequencerIdentity.map(identityConfig =>
        new SequencerIdentityConfig(sequencerId, identityConfig.availableAfter)
      ),
      config.physicalSynchronizers,
    )

  private def withMediatorId(config: SynchronizerNodeConfig, mediatorId: String) =
    new SynchronizerNodeConfig(
      config.cometBft,
      config.sequencer,
      config.mediator.map(_ => new MediatorConfig(mediatorId)),
      config.scan,
      config.legacySequencerConfig,
      config.sequencerIdentity,
      config.physicalSynchronizers,
    )
}
