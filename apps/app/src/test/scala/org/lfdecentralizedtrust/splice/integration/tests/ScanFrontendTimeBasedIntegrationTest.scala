package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.JsonObject
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  ValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfigs.scanStorageConfigV1
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger

import java.time.{Duration, Instant}
import scala.concurrent.Future
import org.openqa.selenium.By
import spray.json.DefaultJsonProtocol.StringJsonFormat

class ScanFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTest("scan-ui")
    with AmuletConfigUtil
    with WalletTestUtil
    with WalletFrontendTestUtil
    with TimeTestUtil
    with SynchronizerFeesTestUtil
    with TriggerTestUtil
    with VotesFrontendTestUtil
    with ValidatorLicensesFrontendTestUtil
    with SvTestUtil {

  val amuletPrice = 2

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.copy(
            // By default, the acs snapshot trigger processes 30sec of history per invocation,
            // which is too slow for this test which advances time by hours or days.
            acsSnapshotTriggerPollingInterval = Some(NonNegativeFiniteDuration.ofHours(1))
          )
        )(config)
      )

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  "A scan UI" should {
    "see DSO and Amulet Info" in { implicit env =>
      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan homepage and switch to the Network Info Tab", {
            go to s"http://localhost:${scanUIPort}"
            eventuallyClickOn(id("navlink-/dso"))
          },
        )(
          "The tabs 'DSO Info' and 'Amulet Info' are visible",
          _ => {
            findAll(id("information-tab-dso-info")).length shouldBe 1
            findAll(id("information-tab-amulet-info")).length shouldBe 1
          },
        )

        actAndCheck(
          "Click on DSO Info", {
            eventuallyClickOn(id("information-tab-dso-info"))
          },
        )(
          "The DSO info is visible",
          _ => {
            val dsoInfo = sv1ScanBackend.getDsoInfo()
            val contract = find(id("dso-rules-information"))
              .map(_.text)
              .map { text =>
                val json =
                  io.circe.parser.parse(text).valueOrFail(s"Couldn't parse JSON from $text")
                json.hcursor
                  .downField("dsoRules")
                  .downField("payload")
                  .as[JsonObject]
                  .valueOrFail(s"Couldn't find dsoRules in $text")
              }
            contract should be(
              Some(
                dsoInfo.dsoRules.contract.payload.asObject
                  .valueOrFail("This is definitely an object.")
              )
            )
          },
        )

        actAndCheck(
          "Click on Amulet Info", {
            eventuallyClickOn(id("information-tab-amulet-info"))
          },
        )(
          "The Amulet info is visible",
          _ => {
            val amuletRules = sv1ScanBackend
              .getAmuletRules()
              .contract
              .toHttp
              .payload
              .asObject
              .valueOrFail("This is definitely an object.")
            find(id("amulet-rules-information"))
              .map(_.text)
              .map(json =>
                io.circe.parser
                  .parse(json)
                  .valueOrFail(s"Couldn't parse JSON from $json")
                  .asObject
                  .valueOrFail(s"Could not decode $json as Amulet rules.")
              ) should be(Some(amuletRules))
          },
        )
      }
    }

    "see expected current and future amulet configurations" in { implicit env =>
      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck("Go to Scan UI main page", go to s"http://localhost:${scanUIPort}")(
          "Check the initial amulet config matches the defaults",
          _ => {
            find(id("holding-fee")).value.text should matchText(
              s"${SpliceUtil.defaultHoldingFee.rate} USD/Round"
            )

            find(id("round-tick-duration")).value.text should matchText {
              // the `.toMinutes` method rounds down to 0
              val minutes = BigDecimal(defaultTickDuration.duration.toSeconds) / 60
              s"${minutes.bigDecimal.stripTrailingZeros.toPlainString} Minutes"
            }
          },
        )
      }

      // Note that the ledger time is in 1970. It will however not change anything because
      // `sv1ScanBackend.getAmuletRules().contract.payload.configSchedule`
      // is a contract such as it was written when it got accepted (e.g. like in 1970).
      // The values are not processed as of now, but the frontend does post-process
      // the Amulet Rules contract to get the actual amulet configurations (see getAmuletConfigurationAsOfNow()).
      val ledgerNow = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
      val javaTomorrow = Instant.now().plusSeconds(86400) // tomorrow

      val newHoldingFee = 0.1

      actAndCheck(
        "add an amulet configuration effective from tomorrow", {
          val amuletRules =
            sv1ScanBackend.getAmuletRules().contract

          val configs = Seq(
            (
              Some(Duration.between(ledgerNow.toInstant, javaTomorrow)), // effective in 1 day
              mkUpdatedAmuletConfig(
                amuletRules,
                defaultTickDuration,
                holdingFee = 3 * newHoldingFee,
              ),
              amuletRules.payload.configSchedule.initialValue,
            )
          )
          setAmuletConfig(configs)
          advanceTime(Duration.ofSeconds(70))
        },
      )(
        "check that the next change will be applied in 24 hours",
        _ => {
          withFrontEnd("scan-ui") { implicit webDriver =>
            find(id("holding-fee")).value.text should matchText(
              s"${sv1ScanBackend.getAmuletRules().contract.payload.configSchedule.initialValue.transferConfig.holdingFee.rate} USD/Round"
            )

            find(id("next-config-update-time")).value.text should equal("1 day").or(
              equal("About 24 hours")
            )
          }
        },
      )

    }

    "see open rounds" in { implicit env =>
      def fmtTime(i: java.time.Instant) = {
        import java.time.*
        format.DateTimeFormatter
          .ofPattern("yyyy-MM-dd HH:mm")
          .format(LocalDateTime.ofInstant(i, ZoneOffset.UTC))
      }

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck("Go to Scan UI main page", go to s"http://localhost:${scanUIPort}")(
          "Check that open rounds match scan backend",
          _ => {
            val openRounds = sv1ScanBackend
              .getOpenAndIssuingMiningRounds()
              ._1
              .map(_.payload)
              .sortBy(_.round.number)
            openRounds should not be empty withClue "open rounds"
            val shownRounds = findAll(className("open-mining-round-row")).toList
            shownRounds should have size openRounds.size.toLong withClue "'Open Mining Rounds' table rows"
            forEvery(shownRounds zip openRounds) { case (shownRound, openRound) =>
              def rt(n: String) = shownRound.childElement(className(n)).text
              rt("round-number") should matchText(openRound.round.number.toString)
              rt("round-opens-at") should matchText(fmtTime(openRound.opensAt))
              rt("round-target-closes-at") should matchText(fmtTime(openRound.targetClosesAt))
            }
          },
        )
      }
    }

    "See expected total amulet balance" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      clue(
        "Wait for backfilling to complete, as the ACS snapshot trigger is paused until then"
      ) {
        eventually() {
          sv1ScanBackend.automation.updateHistory
            .getBackfillingState()
            .futureValue should be(BackfillingState.Complete)
          advanceTime(sv1ScanBackend.config.automation.pollingInterval.asJava)
        }
      }

      val startTime = getLedgerTime

      advanceTime(
        java.time.Duration
          .ofHours(scanStorageConfigV1.dbAcsSnapshotPeriodHours.toLong)
          .plusSeconds(1L)
      )

      eventually() {
        val snapshot = sv1ScanBackend.getDateOfMostRecentSnapshotBefore(
          getLedgerTime,
          migrationId,
        )
        snapshot should not be None
        snapshot.value.toInstant shouldBe >(startTime.toInstant)
      }

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI main page",
          go to s"http://localhost:${scanUIPort}",
        )(
          "See valid total amulet balance",
          _ => {
            val totalText = seleniumText(find(id("total-amulet-balance-amulet")))
            val totalBalance = BigDecimal(
              sv1ScanBackend
                .lookupInstrument("Amulet")
                .flatMap(_.totalSupply)
                .valueOrFail("Amulet balance not yet computed")
            )
            parseAmountText(totalText, amuletNameAcronym) shouldBe totalBalance
            val totalUsdText = seleniumText(find(id("total-amulet-balance-usd")))
            val totalUsdBalance = totalBalance * amuletPrice
            parseAmountText(totalUsdText, "USD") shouldBe totalUsdBalance
          },
        )
      }
    }

    "see the votes" in { implicit env =>
      val dsoInfo = sv1Backend.getDsoInfo()
      val amuletRules = dsoInfo.amuletRules

      val baseAmuletConfig = amuletRules.payload.configSchedule.initialValue

      val newMaxNumInputs = baseAmuletConfig.transferConfig.maxNumInputs.toInt + 1
      val newAmuletConfig = mkUpdatedAmuletConfig(
        amuletRules.contract,
        NonNegativeFiniteDuration.tryFromDuration(
          scala.concurrent.duration.Duration.fromNanos(
            baseAmuletConfig.tickDuration.microseconds * 1000
          )
        ),
        newMaxNumInputs,
      )

      val mockVoteAction = new ARC_AmuletRules(
        new CRARC_SetConfig(
          new AmuletRules_SetConfig(
            newAmuletConfig,
            baseAmuletConfig,
          )
        )
      )

      // only 1 SV in this test suite, so the vote is approved
      sv1Backend.createVoteRequest(
        dsoInfo.svParty.toProtoPrimitive,
        mockVoteAction,
        "url",
        "Testing Testingaton",
        dsoInfo.dsoRules.payload.config.voteRequestTimeout,
        None,
      )

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI for votes",
          go to s"http://localhost:$scanUIPort/governance",
        )(
          "See the vote as executed",
          _ => {
            closeVoteModalsIfOpen

            eventuallyClickOn(id("tab-panel-executed"))
            val rows = getAllVoteRows("sv-vote-results-executed-table-body")

            forExactly(1, rows) { reviewButton =>
              closeVoteModalsIfOpen
              reviewButton.underlying.click()

              // TODO(#934): needs to be changed by using parseAmuletConfigValue() once the diff exists for the first change
              try {
                val newScheduleItem = webDriver.findElement(By.id("accordion-details"))
                val json = newScheduleItem.findElement(By.tagName("pre")).getText
                spray.json
                  .JsonParser(json)
                  .asJsObject("transferConfig")
                  .fields("transferConfig")
                  .asJsObject
                  .fields("maxNumInputs")
                  .convertTo[String] should be(newMaxNumInputs.toString)
              } catch {
                case _: NoSuchElementException => false
              }
            }
          },
        )
      }
    }

    "see amulet price votes" in { implicit env =>
      clue("SVs update amulet prices") {
        eventuallySucceeds() {
          sv1Backend.updateAmuletPriceVote(BigDecimal(1.11))
        }
      }

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to scan UI homepage",
          go to s"http://localhost:${scanUIPort}",
        )(
          "Switch to the Amulet Prices tab",
          _ => {
            inside(find(id("navlink-/amulet-price-votes"))) { case Some(navlink) =>
              navlink.underlying.click()
            }
            val amuletPriceRows = findAll(className("amulet-price-table-row")).toList

            amuletPriceRows.size shouldBe 1

            amuletPriceShouldMatch(amuletPriceRows, sv1Backend.getDsoInfo().svParty, s"1.11 USD")
          },
        )

      }
    }

  }

  private def amuletPriceShouldMatch(
      rows: Seq[Element],
      svParty: PartyId,
      amuletPrice: String,
  ) = {
    forExactly(1, rows) { row =>
      seleniumText(row.childElement(className("sv-party"))) shouldBe svParty.toProtoPrimitive
      row.childElement(className("amulet-price")).text shouldBe amuletPrice
    }
  }
}

case class HasAmuletRulesWrapper(amuletRules: Contract[AmuletRules.ContractId, AmuletRules])
    extends HasAmuletRules {
  override def getAmuletRules()(implicit
      tc: TraceContext
  ): Future[Contract[AmuletRules.ContractId, AmuletRules]] =
    Future.successful(amuletRules)
}
