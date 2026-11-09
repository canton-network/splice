// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import cats.data.EitherT
import com.digitalasset.base.error.utils.ErrorDetails
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.ExternalPartyAmuletRules
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
}
import com.digitalasset.canton.participant.admin.party.PartyManagementServiceError
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting.DsoPartyMigrationFailure
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.SnapshotKey
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import io.grpc.StatusRuntimeException
import java.nio.file.Path
import scala.annotation.unused
import scala.concurrent.{ExecutionContextExecutor, Future}

class DsoPartyMigration(
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    participantAdminConnection: ParticipantAdminConnection,
    @unused ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    dsoPartyHosting: DsoPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  private val dsoStore = dsoStoreWithIngestion.store
  private val dsoParty = dsoStore.key.dsoParty
  private val svParty = dsoStore.key.svParty
  private val partyHosting = new SponsorDsoPartyHosting(
    participantAdminConnection,
    dsoParty,
    dsoPartyHosting,
    loggerFactory,
  )
  private val temporaryCodes = Set(
    Status.Code.FAILED_PRECONDITION,
    Status.Code.NOT_FOUND,
    Status.Code.UNIMPLEMENTED,
  )

  def authorizeParticipantForHostingDsoParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext): EitherT[Future, DsoPartyMigrationFailure, SnapshotKey.Acs] = {
    logger.info(s"Sponsor SV authorizing DSO party to participant $participantId")
    EitherT(
      (for {
        dsoRules <- EitherT.liftF(dsoStore.getDsoRules())
        // this will wait until the PartyToParticipant state change completed
        _ <- partyHosting
          .authorizeDsoPartyToParticipant(
            dsoRules.domain,
            participantId,
          )
        activationTx <- participantAdminConnection
          .getDsoPartyToParticipantTransaction(
            dsoRules.domain,
            participantId,
            dsoParty,
          )
          .toRight[DsoPartyMigrationFailure](DsoPartyHosting.AcceptedStateNotFound(participantId))
        activationTime = activationTx.base.validFrom
        _ = logger.info(
          s"DSO party was authorized on $participantId, preparing snapshot at time $activationTime."
        )
      } yield SnapshotKey.Acs(dsoRules.domain, dsoParty, activationTime)).value
        .recoverWith(temporarilyUnavailable(participantId))
    )
  }

  private def temporarilyUnavailable[T](
      participantId: ParticipantId
  ): PartialFunction[Throwable, Future[T]] = {
    case e: StatusRuntimeException if temporaryCodes.contains(e.getStatus.getCode) =>
      Future.failed(
        Status.UNAVAILABLE
          .withDescription(s"DSO party authorization for $participantId is temporarily unavailable")
          .withCause(e)
          .asRuntimeException()
      )
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def exportSnapshotToFile(key: SnapshotKey.Acs, targetParticipantId: ParticipantId, file: Path)(
      implicit tc: TraceContext
  ): Future[Unit] = {

    def submitDummyTransaction(): Future[Unit] =
      svStoreWithIngestion
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(
          Seq(svParty),
          Seq.empty,
          // The transaction here is arbitrary.
          // ExternalPartyAmuletRules just happens to be one of the simplest templates we have.
          new ExternalPartyAmuletRules(svParty.toProtoPrimitive).createAnd
            .exerciseArchive(),
        )
        .withSynchronizerId(key.synchronizerId)
        .noDedup
        .yieldUnit()

    for {
      snapshot <- {
        retryProvider.retry(
          RetryFor.ClientCalls,
          "download_acs_snapshot",
          show"Download ACS snapshot for DSO at ${key.activationTime}",
          participantAdminConnection
            .exportPartyAcsToFile(
              key.party,
              key.synchronizerId,
              targetParticipantId,
              key.activationTime,
              file,
            )
            .recoverWith { case ex: StatusRuntimeException =>
              val errorDetails = ErrorDetails.from(ex: StatusRuntimeException)
              for {
                _ <- MonadUtil.sequentialTraverse_(errorDetails) {
                  case ErrorDetails.ErrorInfoDetail(
                        PartyManagementServiceError.UnprocessedRequestedTimestamp.id,
                        metadata,
                      ) =>
                    logger.info(
                      s"Requested record time ${key.activationTime} is not yet clean: $metadata, submitting dummy transaction"
                    )
                    submitDummyTransaction()
                  case _ => Future.unit
                }
              } yield {
                // rethrow to trigger the retry
                throw ex
              }
            },
          logger,
        )
      }
    } yield snapshot

  }

}
