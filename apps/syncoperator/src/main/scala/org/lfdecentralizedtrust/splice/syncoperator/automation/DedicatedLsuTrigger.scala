// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.lsu.LsuTransferTriggerBase
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode

import java.nio.file.Path
import scala.concurrent.ExecutionContext

/** Upgrades this operator's synchronizer. The operator owns both nodes, so initializing the
  * successor is the whole job.
  */
class DedicatedLsuTrigger(
    baseContext: TriggerContext,
    localSynchronizerNodes: LocalSynchronizerNodes[SyncOperatorSynchronizerNode],
    successorSynchronizerNode: SyncOperatorSynchronizerNode,
    dumpPath: Path,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends LsuTransferTriggerBase[SyncOperatorSynchronizerNode](
      baseContext,
      localSynchronizerNodes,
      successorSynchronizerNode,
      dumpPath,
    )
