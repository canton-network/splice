package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.sv.config.SvCantonIdentifierConfig
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.IsTheCantonSequencerBFTEnabled
import org.scalatest.Suite

trait StandaloneCanton extends PostgresAroundEach with NamedLogging with ProcessTestUtil {
  self: Suite & BaseTest =>

  // While tests do not run in parallel, their initialization does, so we force using unique DB names
  def dbsSuffix: String

  override def usesDbs = {
    Seq(
      s"sequencer_driver_${dbsSuffix}",
      s"participant_extra_$dbsSuffix",
      s"participant_second_extra_$dbsSuffix",
    ) ++
      (1 to 4).flatMap(index =>
        Seq(
          s"participant_sv${index}_${dbsSuffix}",
          s"sequencer_sv${index}_${dbsSuffix}",
          s"sequencer_sv${index}_${dbsSuffix}_bft",
          s"mediator_sv${index}_${dbsSuffix}",
        )
      )
  }

  def withCantonSvNodes[A](
      adminUsersFromSvBackends: (
          Option[SvAppBackendReference],
          Option[SvAppBackendReference],
          Option[SvAppBackendReference],
          Option[SvAppBackendReference],
      ),
      logSuffix: String,
      svs123: Boolean = true,
      sv4: Boolean = true,
      participants: Boolean = true,
      sequencersMediators: Boolean = true,
      overrideSvDbsSuffix: Option[String] = None,
      overrideSequencerDriverDbSuffix: Option[String] = None,
      portsRange: Option[Int] = None,
      extraParticipantsConfigFileNames: Seq[String] = Seq.empty,
      extraParticipantsEnvMap: Map[String, String] = Map.empty,
      enableBftSequencer: Boolean = IsTheCantonSequencerBFTEnabled,
      extraSequencerConfig: Seq[String] = Seq.empty,
      enableKeyDownload: Boolean = false,
      // The SVs whose participant Canton should initialize. Narrow this for
      // tests that set a participant's identity up themselves.
      autoInitParticipants: Set[Int] = Set(1, 2, 3, 4),
  )(extraEnv: (String, String)*)(test: => A)(implicit tc: TraceContext): A = {

    def conditionalConf(condition: Boolean, filename: String) =
      if (condition) {
        Seq(testResourcesPath / filename)
      } else {
        Seq()
      }

    val configs =
      Seq(testResourcesPath / "include" / "canton-basic.conf") ++
        conditionalConf(svs123 && participants, "standalone-participants-sv123.conf") ++
        (1 to 3).flatMap(i =>
          conditionalConf(
            svs123 && participants && autoInitParticipants.contains(i),
            s"standalone-participant-sv${i}-auto-init.conf",
          )
        ) ++
        conditionalConf(
          svs123 && sequencersMediators,
          "standalone-sequencers-mediators-sv123.conf",
        ) ++
        conditionalConf(
          svs123 && sequencersMediators && enableBftSequencer,
          "standalone-sequencers-sv123-extra-enable-bft.conf",
        ) ++
        conditionalConf(sv4 && participants, "standalone-participant-sv4.conf") ++
        conditionalConf(
          sv4 && participants && autoInitParticipants.contains(4),
          "standalone-participant-sv4-auto-init.conf",
        ) ++
        conditionalConf(sv4 && sequencersMediators, "standalone-sequencer-mediator-sv4.conf") ++
        conditionalConf(
          sv4 && sequencersMediators && enableBftSequencer,
          "standalone-sequencer-sv4-extra-enable-bft.conf",
        ) ++
        extraParticipantsConfigFileNames.toList.map(testResourcesPath / _)

    def adminUserEnv(index: Integer) = {
      adminUsersFromSvBackends
        .productElement(index - 1)
        .asInstanceOf[Option[SvAppBackendReference]]
        .map(s"SV${index}_ADMIN_USER" -> _.config.ledgerApiUser)
    }

    // Canton auto-initializes these participants, so it needs the same node
    // identifier the SV app expects. Tests may rename it, e.g. through
    // `withCantonNodeNameSuffix`, so we read it off the app config.
    def participantIdentifierEnv(index: Integer) = {
      adminUsersFromSvBackends
        .productElement(index - 1)
        .asInstanceOf[Option[SvAppBackendReference]]
        .map(backend =>
          s"SV${index}_PARTICIPANT_IDENTIFIER" -> backend.config.cantonIdentifierConfig
            .getOrElse(SvCantonIdentifierConfig.default(backend.config))
            .participant
        )
    }

    val dbNamesEnv = {
      val svDbsSuffix = overrideSvDbsSuffix.getOrElse(dbsSuffix)
      val sequencerDriverDbSuffix = overrideSequencerDriverDbSuffix.getOrElse(dbsSuffix)
      (1 to 4).flatMap(i =>
        Seq(
          s"SV${i}_PARTICIPANT_DB" -> s"participant_sv${i}_${svDbsSuffix}",
          s"SV${i}_SEQUENCER_DB_BFT" -> s"sequencer_sv${i}_${svDbsSuffix}_bft",
          s"SV${i}_SEQUENCER_DB" -> s"sequencer_sv${i}_${svDbsSuffix}",
          s"SV${i}_MEDIATOR_DB" -> s"mediator_sv${i}_${svDbsSuffix}",
        )
      ) :+
        "SEQUENCER_DRIVER_DB" -> s"sequencer_driver_${sequencerDriverDbSuffix}"
    }

    val portsEnv = portsRange.fold(Seq(): Seq[(String, String)])(range =>
      (1 to 4).flatMap(i =>
        Seq(
          s"SV${i}_PARTICIPANT_LEDGER_API_PORT" -> (range * 1000 + i * 100 + 1).toString,
          s"SV${i}_PARTICIPANT_ADMIN_API_PORT" -> (range * 1000 + i * 100 + 2).toString,
          s"SV${i}_MEDIATOR_ADMIN_API_PORT" -> (range * 1000 + i * 100 + 7).toString,
          s"SV${i}_SEQUENCER_PUBLIC_API_PORT" -> (range * 1000 + i * 100 + 8).toString,
          s"SV${i}_SEQUENCER_ADMIN_API_PORT" -> (range * 1000 + i * 100 + 9).toString,
          s"SV${i}_SEQUENCER_BFT_PORT" -> (range * 1000 + i * 100 + 10).toString,
        )
      )
    )

    val allExtraEnv =
      (extraEnv ++
        (1 to 4).flatMap(adminUserEnv(_)) ++
        (1 to 4).flatMap(participantIdentifierEnv(_)) ++
        portsEnv ++
        dbNamesEnv) ++ extraParticipantsEnvMap.toList

    logger.debug(
      s"""
         |Starting standalone canton with log suffix \n
         |  $logSuffix\n
         |and config files:\n
         |  ${configs.mkString("\n  ")}\n
         |and extra env variables:\n
         |  ${allExtraEnv.mkString("\n  ")}
         |""".stripMargin
    )(tc)
    val sequencers = (if (svs123) Seq(1, 2, 3) else Seq()) ++ (if (sv4) Seq(4) else Seq())
    withCanton(
      configs,
      extraSequencerConfig.flatMap(config =>
        sequencers.map(id => s"canton.sequencers.sv${id}StandaloneSequencer.$config")
      ) ++ Option.when(enableKeyDownload)(ProcessTestUtil.enableKeyDownloadConfig) :+
        // As in production, SV participants serve the repair service for Scan's ACS snapshot endpoint
        ProcessTestUtil.enableRepairCommandsConfig,
      logSuffix,
      allExtraEnv*
    )(test)
  }
}
