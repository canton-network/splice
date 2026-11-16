// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.unavailableparties

import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.error.TransactionRoutingError
import com.digitalasset.canton.ledger.error.groups.CommandExecutionErrors
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.protocol.TransactionProcessor
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import io.grpc.StatusRuntimeException
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.util.UnresponsiveParties

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Executes a batch of tasks, isolating unavailable parties via batch splitting.
  *
  * The batch is pre-sorted ascending by the lowest package version supported by the informees of
  * each task, so that tasks likely to fail are grouped together and isolated by fewer splits
  * instead of being spread across halves that would otherwise succeed.
  * If a submission is rejected because some parties were unresponsive, those parties are named by the mediator and
  * are ignored directly. Any other failure is isolated by splitting the batch in half and retrying
  * the halves in DFS order. Once a batch of size 1 fails, the parties with the
  * lowest supported version on that task are marked as unavailable in the
  * [[org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore]], unless they are on the safety
  * list of protected parties.
  * On success, all parties involved in the submitted batch are removed from the store, resetting
  * their backoff.
  */
class UnavailablePartiesDetector(
    lookupSupportedVersion: PartyId => TraceContext => Future[Option[PackageVersion]],
    unavailablePartiesStore: UnavailablePartiesStore,
    protectedParties: Set[PartyId],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  import UnavailablePartiesDetector.*

  def processBatch[C](
      batch: Seq[C],
      parties: C => Set[PartyId],
      submit: Seq[C] => Future[Unit],
  )(implicit tc: TraceContext): Future[Result] =
    if (batch.isEmpty) Future.successful(Result.empty)
    else
      for {
        // the lowest supported versions are looked up once per party for the whole batch
        // and reused by the split batches for both the pre-sorting and the failure attribution
        lowestSupportedVersions <- lookupLowestSupportedVersions(batch.flatMap(parties).toSet)
        sortedBatch = sortBySupportedVersionAscending(batch, parties, lowestSupportedVersions)
        result <- processSplitBatches(
          List(sortedBatch),
          parties,
          submit,
          lowestSupportedVersions,
          Result.empty,
        )
      } yield result

  // TODO(#8428): move this to addParties
  private def withoutProtected(parties: Set[PartyId]): Set[PartyId] = parties -- protectedParties

  private def ignoreParties(
      parties: Set[PartyId]
  )(implicit tc: TraceContext): Future[Set[PartyId]] = {
    val toIgnore = withoutProtected(parties)
    if (toIgnore.isEmpty) Future.successful(toIgnore)
    else unavailablePartiesStore.addParties(toIgnore.toSeq).map(_ => toIgnore)
  }

  private def lookupLowestSupportedVersions(
      parties: Set[PartyId]
  )(implicit tc: TraceContext): Future[LowestSupportedVersions] =
    MonadUtil
      .sequentialTraverse(parties.toSeq.sortBy(_.toProtoPrimitive))(p =>
        lookupSupportedVersion(p)(tc).map(p -> _)
      )
      .map(_.toMap)

  /** Sorts the tasks ascending by the minimal package version supported by all informees. Tasks
    * for which no version could be determined (None) are placed at the front.
    */
  private def sortBySupportedVersionAscending[C](
      batch: Seq[C],
      parties: C => Set[PartyId],
      lowestSupportedVersions: LowestSupportedVersions,
  ): Seq[C] =
    batch.sortBy(c => parties(c).toSeq.map(versionOf(lowestSupportedVersions, _)).minOption.flatten)

  private def versionOf(
      lowestSupportedVersions: LowestSupportedVersions,
      party: PartyId,
  ): Option[PackageVersion] =
    lowestSupportedVersions.getOrElse(party, None)

  /** Submits the batches on top of the stack, one at a time, splitting a batch in two on a
    * vetting failure and pushing the halves back onto the stack. Tasks involving parties that get
    * ignored are removed from the whole stack right away.
    */
  private def processSplitBatches[C](
      stack: List[Seq[C]],
      partiesOf: C => Set[PartyId],
      submit: Seq[C] => Future[Unit],
      lowestSupportedVersions: LowestSupportedVersions,
      result: Result,
  )(implicit tc: TraceContext): Future[Result] = stack match {
    case Nil => Future.successful(result)
    case current :: rest =>
      submit(current).transformWith {
        // On success, remove all involved parties from the unavailable parties store to reset their backoff, go on with rest.
        case Success(_) =>
          val involvedParties = current.flatMap(partiesOf).toSet
          unavailablePartiesStore
            .removeParties(involvedParties.toSeq)
            .flatMap { restored =>
              if (restored.nonEmpty)
                logger.info(
                  s"Submission succeeded, recovered ${restored.size} unavailable parties: $restored"
                )
              processSplitBatches(
                rest,
                partiesOf,
                submit,
                lowestSupportedVersions,
                result.addSubmitted(current.size),
              )
            }
        // On Failure:
        //  - if the mediator named the unresponsive parties, ignore exactly those: the whole
        //    batch is rejected.
        //  - if the batch has more than one task, split it in half and retry both halves.
        //  - if the batch has only one task, mark the parties with the lowest supported version as unavailable.
        case Failure(UnresponsivePartiesFailure(unresponsiveParties))
            if shrinks(current, partiesOf, withoutProtected(unresponsiveParties)) =>
          ignoreParties(unresponsiveParties).flatMap { toIgnore =>
            logger.info(
              s"Batch of ${current.size} tasks was rejected due to unresponsive parties, ignoring $toIgnore"
            )
            processRemaining(
              toIgnore,
              current :: rest,
              partiesOf,
              submit,
              lowestSupportedVersions,
              result,
              failedTasks = 0,
            )
          }
        case Failure(reason @ VettingFailure()) if current.size > 1 =>
          val (left, right) = current.splitAt(current.size / 2)
          logger.info(
            s"Splitting batch of ${current.size} tasks into ${left.size} and ${right.size} after failure: $reason"
          )
          processSplitBatches(
            left :: right :: rest,
            partiesOf,
            submit,
            lowestSupportedVersions,
            result.addSplit(),
          )
        case Failure(reason @ VettingFailure()) =>
          val involvedParties = current.headOption.fold(Set.empty[PartyId])(partiesOf)
          logger.info(
            s"Isolated failure to a single task with parties $involvedParties: $reason"
          )
          val candidates =
            partiesWithMinimalSupportedVersion(involvedParties, lowestSupportedVersions)
          for {
            result <- ignoreParties(candidates).flatMap { toIgnore =>
              if (toIgnore.isEmpty) {
                logger.warn(
                  s"Not ignoring any party for the failing task, all candidates ($candidates) are protected, failing the batch so it is retried"
                )
                Future.failed(reason)
              } else
                processRemaining(
                  toIgnore,
                  rest,
                  partiesOf,
                  submit,
                  lowestSupportedVersions,
                  result,
                  failedTasks = 1,
                )
            }
          } yield result
        // Not a vetting failure (e.g. contention): fail the task so it is retried as a whole.
        case Failure(reason) =>
          Future.failed(reason)
      }
  }

  /** Conservatively ignores the parties supporting the lowest package version.
    * Falls back to all involved parties if they all support the same version or if no version could be determined.
    */
  private def partiesWithMinimalSupportedVersion(
      parties: Set[PartyId],
      lowestSupportedVersions: LowestSupportedVersions,
  ): Set[PartyId] = {
    val partyLowestSupportedVersions =
      parties.toSeq.map(p => p -> versionOf(lowestSupportedVersions, p))
    val min = partyLowestSupportedVersions.map(_._2).minOption.flatten
    partyLowestSupportedVersions.collect {
      case (p, v) if v == min => p
    }.toSet
  }

  private def processRemaining[C](
      ignored: Set[PartyId],
      stack: List[Seq[C]],
      partiesOf: C => Set[PartyId],
      submit: Seq[C] => Future[Unit],
      lowestSupportedVersions: LowestSupportedVersions,
      result: Result,
      failedTasks: Int,
  )(implicit tc: TraceContext): Future[Result] = {
    val remaining = stack.map(_.filterNot(c => partiesOf(c).exists(ignored.contains)))
    val dropped = stack.map(_.size).sum - remaining.map(_.size).sum
    processSplitBatches(
      remaining.filter(_.nonEmpty),
      partiesOf,
      submit,
      lowestSupportedVersions,
      result.addIgnored(ignored, failedTasks = failedTasks + dropped),
    )
  }
}

object UnavailablePartiesDetector {

  private type LowestSupportedVersions = Map[PartyId, Option[PackageVersion]]

  /** Matches failures for which the mediator reported the parties that did not respond. */
  private object UnresponsivePartiesFailure {
    def unapply(t: Throwable): Option[Set[PartyId]] = UnresponsiveParties.fromThrowable(t)
  }

  private[unavailableparties] object VettingFailure {
    private[unavailableparties] val vettingErrorIds: Set[String] = Set(
      // Raised by the submitting participant before sending the transaction: a participant hosting an
      // informee hasn't vetted a package the transaction uses.
      TransactionProcessor.SubmissionErrors.PackageNotVettedByRecipients.id,
      // Raised before interpretation: topology-aware package selection finds no version of a package
      // that is vetted by the participants of all involved parties.
      CommandExecutionErrors.PackageSelectionFailed.id,
      // Raised during interpretation when a contract is used at a different package version than it was
      // created with, and its signatories, observers or key differ under that version.
      CommandExecutionErrors.Interpreter.UpgradeError.ValidationFailed.id,
      // Raised during interpretation when a contract can't be converted to the selected package version,
      // e.g. downgrading it to the version a party vetted would drop a set optional field.
      CommandExecutionErrors.Interpreter.UpgradeError.TranslationFailed.id,
    )

    // NO_SYNCHRONIZER_FOR_SUBMISSION is raised by routing for several reasons: no active participant,
    // a disconnected synchronizer, an unsupported protocol version, or packages unknown to an informee.
    // Only the last one is a vetting issue, so only that message is matched.
    private[unavailableparties] val unknownPackageRoutingMessage =
      "Some packages are not known to all informees"

    def unapply(t: Throwable): Boolean = t match {
      case ex: StatusRuntimeException =>
        ErrorDetails.from(ex).exists {
          case ErrorDetails.ErrorInfoDetail(errorCodeId, _) =>
            vettingErrorIds.contains(errorCodeId) ||
            (errorCodeId == TransactionRoutingError.TopologyErrors.NoSynchronizerForSubmission.id &&
              Option(ex.getStatus.getDescription).exists(_.contains(unknownPackageRoutingMessage)))
          case _ => false
        }
      case _ => false
    }
  }

  /** Ignoring the given parties only pays off if it actually removes tasks from the batch,
    * otherwise the very same batch would be resubmitted and we would loop forever.
    */
  private def shrinks[C](
      batch: Seq[C],
      partiesOf: C => Set[PartyId],
      toIgnore: Set[PartyId],
  ): Boolean =
    toIgnore.nonEmpty && batch.exists(c => partiesOf(c).exists(toIgnore.contains))

  final case class Result(
      submittedTasks: Int,
      failedTasks: Int,
      splits: Int,
      ignoredParties: Set[PartyId],
  ) {
    def addSubmitted(n: Int): Result = copy(submittedTasks = submittedTasks + n)
    def addSplit(): Result = copy(splits = splits + 1)
    def addIgnored(parties: Set[PartyId], failedTasks: Int): Result =
      copy(
        ignoredParties = ignoredParties ++ parties,
        failedTasks = this.failedTasks + failedTasks,
      )

    def summary: String =
      s"submitted $submittedTasks tasks, failed $failedTasks, " +
        s"$splits batch splits, newly ignored parties: ${ignoredParties.size}" +
        (if (ignoredParties.isEmpty) "" else s" ($ignoredParties)")
  }

  object Result {
    val empty: Result = Result(0, 0, 0, Set.empty)
  }
}
