package org.lfdecentralizedtrust.splice.scan.config

import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv2
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object TokenStandardConfig {

  final case class SettlementConfig(
      maxLegs: Int = 100,
      maxParties: Int = 100,
  ) {
    def validateSettleBatch(settleBatch: allocationv2.SettlementFactory_SettleBatch): Unit = {
      val numTransferLegs = settleBatch.transferLegs.size()
      if (numTransferLegs > maxLegs) {
        throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription(
            s"Too many transfer legs in the settle batch: $numTransferLegs. Maximum allowed: $maxLegs"
          )
          .asRuntimeException()
      }

      val numParties = settleBatch.transferLegs.asScala
        .flatMap(leg => Seq(leg.sender, leg.receiver).flatMap(_.owner.toScala))
        .distinct
        .size
      if (numParties > maxParties) {
        throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription(
            s"Too many parties in the settle batch: $numTransferLegs. Maximum allowed: $maxLegs"
          )
          .asRuntimeException()
      }
    }
  }
}
