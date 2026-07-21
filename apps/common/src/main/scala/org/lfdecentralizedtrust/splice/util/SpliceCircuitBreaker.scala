// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.base.error.ErrorCategory
import com.digitalasset.base.error.ErrorCategory.{
  InvalidGivenCurrentSystemStateOther,
  InvalidGivenCurrentSystemStateResourceExists,
  InvalidGivenCurrentSystemStateResourceMissing,
  InvalidGivenCurrentSystemStateSeekAfterEnd,
  InvalidIndependentOfSystemState,
}
import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.LocalRejectError.ConsistencyRejections.LockedContracts
import com.digitalasset.canton.protocol.LocalRejectErrorCode
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.StatusRuntimeException
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.pattern.CircuitBreaker
import org.lfdecentralizedtrust.splice.config.CircuitBreakerConfig

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@SuppressWarnings(Array("org.wartremover.warts.Null"))
class SpliceCircuitBreaker(
    name: String,
    config: CircuitBreakerConfig,
    clock: Clock,
    dsoPartyId: PartyId,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    scheduler: Scheduler,
) extends NamedLogging {

  private val lastFailure: AtomicReference[Option[CantonTimestamp]] = new AtomicReference(None)
  private val lastException: AtomicReference[Option[Throwable]] = new AtomicReference(None)

  private val errorCategoriesToIgnore: Set[ErrorCategory] = Set(
    InvalidIndependentOfSystemState,
    InvalidGivenCurrentSystemStateOther,
    InvalidGivenCurrentSystemStateResourceExists,
    InvalidGivenCurrentSystemStateResourceMissing,
    InvalidGivenCurrentSystemStateSeekAfterEnd,
  )

  private val errorCodesToIgnore: Set[LocalRejectErrorCode] = Set(
    LockedContracts
  )

  private val underlying: CircuitBreaker = new CircuitBreaker(
    scheduler,
    maxFailures = config.maxFailures,
    callTimeout = config.callTimeout.underlying,
    resetTimeout = config.resetTimeout.underlying,
    maxResetTimeout = config.maxResetTimeout.underlying,
    exponentialBackoffFactor = config.exponentialBackoffFactor,
    randomFactor = config.randomFactor,
  ).onOpen {
    logger.warn(
      s"Circuit breaker $name tripped after ${config.maxFailures} failures. Attaching last failure",
      lastException.get().orNull,
    )(TraceContext.empty)
  }.onHalfOpen {
    logger.info(s"Circuit breaker $name moving to half-open state")(TraceContext.empty)
  }.onClose {
    logger.info(s"Circuit breaker $name moving to closed state")(TraceContext.empty)
  }

  def withCircuitBreaker[T](body: => Future[T])(implicit tc: TraceContext): Future[T] = {
    if (underlying.isClosed || underlying.isHalfOpen) {
      callAndMark(body)
    } else {
      Future.failed(
        new SpliceCircuitBreakerOpenException(
          underlying.resetTimeout,
          s"Circuit breaker $name is open, calls are failing fast",
          lastException.get().orNull,
        )
      )
    }
  }

  private def callAndMark[T](body: => Future[T])(implicit tc: TraceContext) = {
    // Only run this when the circuit breaker is closed, otherwise rely on the pekko circuit breaker to handle reopening.
    if (underlying.isClosed) {
      lastFailure.updateAndGet(_.filter { lastFailureTime =>
        val elapsed = clock.now - lastFailureTime
        if (elapsed.compareTo(config.resetFailuresAfter.asJava) >= 0) {
          logger.info(
            s"Resetting circuit breaker as last failure was $elapsed ago which is more than ${config.resetFailuresAfter}"
          )
          underlying.succeed()
          false
        } else {
          true
        }
      })
    }

    body.andThen {
      case Failure(exception) =>
        if (!isFailureIgnored(exception)) {
          underlying.fail()
          lastFailure.set(Some(clock.now))
          lastException.set(Some(exception))
        }
      case Success(_) =>
        underlying.succeed()
        lastException.set(None)
    }
  }

  private def isFailureIgnored[T](result: Throwable): Boolean = {
    result match {
      case ex: StatusRuntimeException =>
        ErrorDetails
          .from(ex)
          .collect {
            case UnresponsiveParties(parties) =>
              !parties.contains(dsoPartyId)
            case ErrorDetails.ErrorInfoDetail(errorCodeId, metadata)
                if metadata.contains("category") =>
              val categoryIgnored = metadata
                .get("category")
                .flatMap(_.toIntOption)
                .flatMap(ErrorCategory.fromInt)
                .exists(failureCategory => errorCategoriesToIgnore.contains(failureCategory))
              val codeIgnored = errorCodesToIgnore.exists(_.id == errorCodeId)
              categoryIgnored || codeIgnored
          }
          .exists(identity)
      case _ => false
    }
  }

  def isOpen: Boolean = underlying.isOpen
  def isClosed: Boolean = underlying.isClosed
  def isHalfOpen: Boolean = underlying.isHalfOpen

}

object SpliceCircuitBreaker {

  def apply(
      name: String,
      config: CircuitBreakerConfig,
      clock: Clock,
      dsoPartyId: PartyId,
      loggerFactory: NamedLoggerFactory,
  )(implicit scheduler: Scheduler, ec: ExecutionContext): SpliceCircuitBreaker =
    new SpliceCircuitBreaker(
      name,
      config,
      clock,
      dsoPartyId,
      loggerFactory,
    )
}

class SpliceCircuitBreakerOpenException(
    val remainingDuration: FiniteDuration,
    message: String,
    cause: Throwable,
) extends RuntimeException(message, cause)
