// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import com.digitalasset.canton.admin.api.client.data.{
  SequencerConnectionPoolDelays,
  SubmissionRequestAmplification,
  SubscriptionLivenessLimits,
}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.sv.config.SvSequencerConfig

import java.time.Duration

abstract class SvSynchronizerNode(
    override val sequencerAdminConnection: SequencerAdminConnection,
    val mediatorAdminConnection: MediatorAdminConnection,
    val sequencerExternalPublicUrl: String,
    val sequencerAvailabilityDelay: Duration,
    val sequencerConfig: SequencerConfig,
    val mediatorSequencerAmplification: SubmissionRequestAmplification,
    val mediatorSequencerConnectionPoolDelays: SequencerConnectionPoolDelays,
    val mediatorSubscriptionLivenessLimits: SubscriptionLivenessLimits,
) extends SynchronizerNode(sequencerAdminConnection) {}

sealed trait SequencerConfig {}

object SequencerConfig {
  def fromConfig(
      sequencerConfig: SvSequencerConfig
  ): SequencerConfig = {
    // TODO (#8263): remove the reference sequencer case, DA BFT is the only supported sequencer
    if (sequencerConfig.isCantonBftSequencer) {
      CantonBftSequencerConfig()
    } else {
      ReferenceSequenceConfig()
    }
  }
}

final case class CantonBftSequencerConfig(
) extends SequencerConfig

final case class ReferenceSequenceConfig(
) extends SequencerConfig
