// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.syncoperator.{SyncOperatorApp, SyncOperatorAppBootstrap}
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorAppBackendConfig
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory

/** Sync operator app instances. */
class SyncOperatorApps(
    create: (String, SyncOperatorAppBackendConfig) => SyncOperatorAppBootstrap,
    _timeouts: ProcessingTimeout,
    configs: Map[String, SyncOperatorAppBackendConfig],
    parametersFor: String => SharedSpliceAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[
      SyncOperatorApp,
      SyncOperatorAppBackendConfig,
      SharedSpliceAppParameters,
      SyncOperatorAppBootstrap,
    ](
      create,
      _timeouts,
      configs,
      parametersFor,
      startUpGroup = 0,
      _loggerFactory,
    ) {}
