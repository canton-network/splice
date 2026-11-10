// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.domain

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.sequencing.{
  SequencerConnectionPoolDelays,
  SequencerConnections,
  SubmissionRequestAmplification,
  SubscriptionLivenessLimits,
}
import com.digitalasset.canton.topology.{SequencerId, UniqueIdentifier}
import com.digitalasset.nonempty.NonEmpty
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DsoSequencer
import org.scalatest.wordspec.AnyWordSpec

class SynchronizerConnectorTest extends AnyWordSpec with BaseTest {

  private val now = CantonTimestamp.Epoch.plusSeconds(100)
  private val sequencer1Id = SequencerId(UniqueIdentifier.tryFromProtoPrimitive("seq::1"))
  private val sequencer2Id = SequencerId(UniqueIdentifier.tryFromProtoPrimitive("seq::2"))

  private def dsoSequencer(
      id: SequencerId,
      url: String,
      availableAfter: CantonTimestamp,
      svName: String,
  ) =
    DsoSequencer(0, id, url, svName, availableAfter.toInstant)

  "extractValidConnections" should {
    "expect each connection to be on the sequencer published for its url" in {
      val connections = SynchronizerConnector.extractValidConnections(
        Seq(
          dsoSequencer(sequencer1Id, "http://sequencer1:5008", CantonTimestamp.Epoch, "sv1"),
          dsoSequencer(sequencer2Id, "http://sequencer2:5008", CantonTimestamp.Epoch, "sv2"),
        ),
        now,
      )
      connections.map(connection =>
        (connection.sequencerAlias.unwrap, connection.sequencerId)
      ) shouldBe Seq(
        sequencer1Id.toProtoPrimitive -> Some(sequencer1Id),
        sequencer2Id.toProtoPrimitive -> Some(sequencer2Id),
      )
    }

    "skip sequencers without url or not yet available" in {
      val connections = SynchronizerConnector.extractValidConnections(
        Seq(
          dsoSequencer(sequencer1Id, "", CantonTimestamp.Epoch, "sv1"),
          dsoSequencer(sequencer2Id, "http://sequencer2:5008", now.plusSeconds(1), "sv2"),
        ),
        now,
      )
      connections shouldBe empty
    }

    "keep a connection per SV with a distinct alias when several SVs publish the same sequencer id" in {
      val connections = SynchronizerConnector.extractValidConnections(
        Seq(
          dsoSequencer(sequencer1Id, "http://sequencer1:5008", CantonTimestamp.Epoch, "sv1"),
          dsoSequencer(sequencer2Id, "http://sequencer2:5008", CantonTimestamp.Epoch, "sv2"),
          dsoSequencer(sequencer1Id, "http://attacker:5008", CantonTimestamp.Epoch, "sv3"),
        ),
        now,
      )
      connections.map(connection =>
        (connection.sequencerAlias.unwrap, connection.sequencerId)
      ) shouldBe Seq(
        s"${sequencer1Id.toProtoPrimitive}-sv1" -> Some(sequencer1Id),
        sequencer2Id.toProtoPrimitive -> Some(sequencer2Id),
        s"${sequencer1Id.toProtoPrimitive}-sv3" -> Some(sequencer1Id),
      )
      SequencerConnections.many(
        NonEmpty.from(connections).value,
        PositiveInt.tryCreate(2),
        NonNegativeInt.zero,
        SubmissionRequestAmplification.NoAmplification,
        SequencerConnectionPoolDelays.default,
        SubscriptionLivenessLimits.default,
      ) shouldBe a[Right[?, ?]]
    }

    "keep a single connection when several SVs publish the same sequencer id and url" in {
      val connections = SynchronizerConnector.extractValidConnections(
        Seq(
          dsoSequencer(sequencer1Id, "http://sequencer1:5008", CantonTimestamp.Epoch, "sv1"),
          dsoSequencer(sequencer1Id, "http://sequencer1:5008", CantonTimestamp.Epoch, "sv2"),
        ),
        now,
      )
      connections.map(connection =>
        (connection.sequencerAlias.unwrap, connection.sequencerId)
      ) shouldBe Seq(sequencer1Id.toProtoPrimitive -> Some(sequencer1Id))
    }
  }
}
