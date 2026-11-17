// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.data.OptionT
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.DsoRulesStore
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait SynchronizerNodeConfigClient {

  protected def getSynchronizerNodeConfigDsoState(dsoStore: SvDsoStore, svParty: PartyId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, (DsoRulesStore.DsoRulesWithSvNodeState, Option[SynchronizerNodeConfig])] = {
    for {
      rulesAndState <- OptionT.liftF(dsoStore.getDsoRulesWithSvNodeState(svParty))
      synchronizerId = rulesAndState.dsoRules.domain
      nodeState = rulesAndState.svNodeState.payload
      synchronizerNodeConfig =
        nodeState.state.synchronizerNodes.asScala.get(
          synchronizerId.toProtoPrimitive
        )
    } yield (rulesAndState, synchronizerNodeConfig)
  }

  protected def updateSynchronizerNodeConfig(
      rulesAndState: DsoRulesStore.DsoRulesWithSvNodeState,
      newSvNodeConfig: SynchronizerNodeConfig,
      store: SvDsoStore,
      connection: SpliceLedgerConnection,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {
    val synchronizerId = rulesAndState.dsoRules.domain
    val cmd = rulesAndState.dsoRules.exercise(
      _.exerciseDsoRules_SetSynchronizerNodeConfig(
        store.key.svParty.toProtoPrimitive,
        synchronizerId.toProtoPrimitive,
        newSvNodeConfig,
        rulesAndState.svNodeState.contractId,
      )
    )
    for {
      _ <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield ()
  }

}
