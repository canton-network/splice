// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator

import org.lfdecentralizedtrust.splice.environment.{SequencerAdminConnection, SynchronizerNode}

/** Connections to a synchronizer node operated by this sync operator. */
final class SyncOperatorSynchronizerNode(
    override val sequencerAdminConnection: SequencerAdminConnection
) extends SynchronizerNode(sequencerAdminConnection)
    with AutoCloseable {
  override def close(): Unit = {
    sequencerAdminConnection.close()
  }
}
