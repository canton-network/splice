// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv2

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object TokenStandardConfig {

  final case class SettlementConfig(
      maxLegs: Int = 100,
      maxParties: Int = 100,
      maxAllocations: Int = 100,
  ) {
    def validateSettleBatch(settleBatch: allocationv2.SettlementFactory_SettleBatch): Unit = {
      val numTransferLegs = settleBatch.transferLegs.size()
      validateNumLegs(numTransferLegs)

      val numParties = settleBatch.transferLegs.asScala
        .flatMap(leg => Seq(leg.sender, leg.receiver).flatMap(_.owner.toScala))
        .distinct
        .size
      validateNumParties(numParties)

      val numAllocations = settleBatch.allocations.size()
      validateNumAllocations(numAllocations)
    }

    def validateAllocate(allocate: allocationinstructionv2.AllocationFactory_Allocate): Unit = {
      val numTransferLegs = allocate.allocation.transferLegSides.size()
      validateNumLegs(numTransferLegs)

      val numParties = allocate.allocation.transferLegSides.asScala
        .flatMap(side => side.otherside.owner.toScala)
        .distinct
        .size
      validateNumParties(numParties)
    }

    private def validateNumLegs(numTransferLegs: Int) = {
      if (numTransferLegs > maxLegs) {
        throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription(
            s"Too many transfer legs in the settle batch: $numTransferLegs. Maximum allowed: $maxLegs"
          )
          .asRuntimeException()
      }
    }

    private def validateNumParties(numParties: Int) = {
      if (numParties > maxParties) {
        throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription(
            s"Too many parties in the settle batch: $numParties. Maximum allowed: $maxParties"
          )
          .asRuntimeException()
      }
    }
    private def validateNumAllocations(numAllocations: Int) = {
      if (numAllocations > maxAllocations) {
        throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription(
            s"Too many allocations in the settle batch: $numAllocations. Maximum allowed: $maxAllocations"
          )
          .asRuntimeException()
      }
    }
  }
}
