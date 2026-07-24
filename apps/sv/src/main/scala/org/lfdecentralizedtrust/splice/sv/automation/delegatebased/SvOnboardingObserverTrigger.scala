// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.SvOnboardingConfirmed
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.{TopologyMapping, VettedPackages}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.DelayUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import scala.util.Random
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class SvOnboardingObserverTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    private val connection: SpliceLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingObserverTrigger.Task] {

  import SvOnboardingObserverTrigger.Task

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      confirmations <- store.listSvOnboardingConfirmed()
    } yield {
      confirmations
        .filter(co => !co.payload.svPartyIsObserver.toScala.exists(_.booleanValue()))
        .map(c => Task(c.contractId, PartyId.tryFromProtoPrimitive(c.payload.svParty)))
    }
  }

  override protected def completeTask(task: Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()

      // which participant hosts this candidate party on the domain
      partyToParticipantMappings <- participantAdminConnection.listPartyToParticipant(
        store = Some(TopologyStoreId.Synchronizer(dsoRules.domain)),
        filterParty = task.partyId.filterString,
      )

      participantIdO = partyToParticipantMappings.headOption.flatMap(
        _.mapping.participants.headOption.map(_.participantId)
      )

      outcome <- participantIdO match {
        case None =>
          Future.successful(
            TaskSuccess(s"Participant mapping for ${task.partyId} not yet found, skipping")
          )

        case Some(participantId) =>
          // query for VettedPackages
          participantAdminConnection
            .listAllTransactions(
              store = TopologyStoreId.Synchronizer(dsoRules.domain),
              timeQuery = TimeQuery.HeadState,
              includeMappings = Set(TopologyMapping.Code.VettedPackages),
            )
            .flatMap { txs =>
              val governancePackageId =
                SvOnboardingConfirmed.TEMPLATE_ID_WITH_PACKAGE_ID.getPackageId

              val hasVetted = txs.exists { tx =>
                tx.mapping match {
                  case vp: VettedPackages if vp.participantId == participantId =>
                    vp.packages.exists(_.packageId == governancePackageId)
                  case _ => false
                }
              }

              if (hasVetted) {
                // Apply a random backoff up to 10 seconds to prevent all SVs from submitting simultaneously
                val delayMillis = Random.nextLong(10000L)
                DelayUtil
                  .delayIfNotClosing(
                    "sv-onboarding-observer-delay",
                    FiniteDuration(delayMillis, TimeUnit.MILLISECONDS),
                    this,
                  )
                  .onShutdown(())
                  .flatMap { _ =>
                    isStaleTask(task).flatMap { isStale =>
                      if (isStale) {
                        Future.successful(
                          TaskSuccess(s"Task for ${task.partyId} became stale during backoff")
                        )
                      } else {
                        val cmd = dsoRules.exercise(
                          _.exerciseDsoRules_MakeSvOnboardingConfirmedObserver(
                            task.contractId,
                            store.key.svParty.toProtoPrimitive,
                          )
                        )

                        connection
                          .submit(
                            actAs = Seq(store.key.svParty),
                            readAs = Seq(store.key.dsoParty),
                            update = cmd,
                          )
                          .noDedup
                          .yieldUnit()
                          .map(_ =>
                            TaskSuccess(
                              s"Made ${task.partyId} an observer of its onboarding confirmation"
                            )
                          )
                      }
                    }
                  }
              } else {
                Future.successful(
                  TaskSuccess(
                    s"Participant $participantId has not vetted governance package yet, waiting"
                  )
                )
              }
            }
      }
    } yield outcome
  }

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    store.multiDomainAcsStore
      .lookupContractById(SvOnboardingConfirmed.COMPANION)(task.contractId)
      .map {
        case None => true
        case Some(c) => c.payload.svPartyIsObserver.toScala.exists(_.booleanValue())
      }
  }
}

object SvOnboardingObserverTrigger {
  final case class Task(
      contractId: SvOnboardingConfirmed.ContractId,
      partyId: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("contractId", _.contractId.contractId.unquoted),
        param("partyId", _.partyId),
      )
  }
}
