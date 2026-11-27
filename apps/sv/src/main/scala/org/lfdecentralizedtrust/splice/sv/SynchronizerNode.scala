// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import com.digitalasset.canton.admin.api.client.data.{
  SequencerConnectionPoolDelays,
  SubmissionRequestAmplification,
  SubscriptionLivenessLimits,
}
import org.lfdecentralizedtrust.splice.environment.*

import java.time.Duration

abstract class SvSynchronizerNode(
    override val sequencerAdminConnection: SequencerAdminConnection,
    val mediatorAdminConnection: MediatorAdminConnection,
    val sequencerExternalPublicUrl: String,
    val sequencerAvailabilityDelay: Duration,
    val mediatorSequencerAmplification: SubmissionRequestAmplification,
    val mediatorSequencerConnectionPoolDelays: SequencerConnectionPoolDelays,
    val mediatorSubscriptionLivenessLimits: SubscriptionLivenessLimits,
) extends SynchronizerNode(sequencerAdminConnection) {}
