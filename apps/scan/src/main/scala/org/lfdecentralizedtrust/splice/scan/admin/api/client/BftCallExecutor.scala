// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.implicits.*
import com.daml.metrics.api.MetricHandle.Timer.TimerHandle
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.LoggerUtil
import com.digitalasset.canton.util.retry.{ErrorKind, ExceptionRetryPolicy}
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes, Uri}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.environment.{BaseAppConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.metrics.ScanConnectionMetrics
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.{
  BftCallConfig,
  ScanConnections,
}
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.slf4j.event.Level

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success, Try}
import scala.jdk.CollectionConverters.*

object BftCallExecutor {
  def bftCallWithScanUris[T](
      connections: ScanConnections,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      call: SingleScanConnection => Future[T],
      endpoint: String,
      callConfig: BftCallConfig,
      consensusFailureLogLevel: Level = Level.WARN,
      disagreementLogLevel: Level = Level.INFO,
      shortenResponsesForLog: T => Any = identity[T],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      loggingContext: com.digitalasset.canton.logging.ErrorLoggingContext,
  ): Future[(T, List[Uri])] = {
    implicit val mc: MetricsContext = MetricsContext("request" -> endpoint)

    def markBftCall(outcome: String): Unit =
      connectionMetrics.foreach { m =>
        MetricsContext.withExtraMetricLabels(("outcome", outcome)) { implicit mc =>
          m.bftCalls.mark()
        }
      }
    def startTimer(): Option[TimerHandle] =
      connectionMetrics.map(_.bftReadLatency.startAsync())
    def stopTimer(t: Option[TimerHandle]): Unit = t.foreach(_.stop())

    if (!callConfig.enoughAvailableScans) {
      val totalNumber = connections.totalNumber
      val msg =
        s"Only ${callConfig.connections.size} scan instances can be used " +
          s"(out of $totalNumber configured ones), which are fewer than the necessary " +
          s"${callConfig.targetSuccess} to achieve BFT guarantees."
      val exception = HttpErrorWithHttpCode(StatusCodes.BadGateway, msg)
      LoggerUtil.logThrowableAtLevel(consensusFailureLogLevel, msg, exception)
      markBftCall("not_enough_scans")
      Future.failed(exception)
    } else {
      val timer = startTimer()

      retryProvider
        .retryForClientCalls(
          "bft_call",
          s"Bft call with ${callConfig.targetSuccess} out of ${callConfig.requestsToDo} matching responses",
          executeCall(
            call,
            requestFrom = Random.shuffle(callConfig.connections).take(callConfig.requestsToDo),
            nTargetSuccess = callConfig.targetSuccess,
            logger,
            shortenResponsesForLog,
            disagreementLogLevel,
            connectionMetrics,
          ),
          logger,
          (_: String) => ConsensusNotReachedRetryable,
        )
        .recoverWith { case c: ConsensusNotReached =>
          LoggerUtil.logThrowableAtLevel(consensusFailureLogLevel, "Consensus not reached.", c)
          markBftCall("consensus_not_reached")
          Future.failed(
            HttpErrorWithHttpCode(
              StatusCodes.BadGateway,
              s"Failed to reach consensus from ${callConfig.requestsToDo} Scan nodes, " +
                s"requiring ${callConfig.targetSuccess} matching responses.",
            )
          )
        }
        .andThen {
          case Failure(_: HttpErrorWithHttpCode) =>
            // Already marked by the recoverWith above ("consensus_not_reached")
            // or by the not_enough_scans branch — nothing more to do.
            stopTimer(timer)
          case Failure(_) =>
            markBftCall("transport_error")
            stopTimer(timer)
          case Success(_) =>
            markBftCall("ok")
            stopTimer(timer)
        }
    }
  }

  def executeCall[T, C <: HasUrl](
      call: C => Future[T],
      requestFrom: Seq[C],
      nTargetSuccess: Int,
      logger: TracedLogger,
      shortenResponsesForLog: T => Any = identity[T],
      disagreementLogLevel: Level = Level.INFO,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      mc: MetricsContext = MetricsContext.Empty,
  ): Future[(T, List[Uri])] = {
    require(requestFrom.nonEmpty, "At least one request must be made.")

    val responses =
      new ConcurrentHashMap[ScanResponse[T], List[Uri]]()
    val nResponsesDone = new AtomicInteger(0)
    val finalResponse = Promise[(T, List[Uri])]()

    requestFrom.foreach { scan =>
      call(scan)
        .transformWith(response => keyToGroupResponses(response).map(_ -> response))
        .foreach { case (key, response) =>
          val agreements =
            responses.compute(
              key,
              (_, scans) => scan.url :: Option(scans).getOrElse(List.empty),
            )

          // In the special case of nTargetSuccess == 1, ignore error responses
          // Otherwise a single HTTP error or network failure would prevent reading the responses from others
          val considerResponseForQuorum = key match {
            case _: ExceptionFailureResponse[?] => !(nTargetSuccess == 1 && requestFrom.size != 1)
            case _ => true
          }
          if (considerResponseForQuorum && agreements.size == nTargetSuccess) { // consensus has been reached
            finalResponse.tryComplete(response.map(r => (r, agreements))): Unit
          }

          if (nResponsesDone.incrementAndGet() == requestFrom.size) { // all Scans are done
            finalResponse.future.value match {
              case None =>
                val exception = ConsensusNotReached(
                  requestFrom.size,
                  responses,
                  shortenResponsesForLog,
                )
                finalResponse.tryFailure(exception): Unit
              case Some(consensusResponse) =>
                logDisagreements(
                  logger,
                  consensusResponse.map(_._1),
                  responses,
                  disagreementLogLevel,
                  connectionMetrics,
                )
            }
          }
        }
    }
    finalResponse.future
  }

  /** Responses are stored in a ConcurrentHashMap. Equality is defined as:
    * - Simple Scala equality when the response is successful (typically, 200 OK).
    * - Status code + response body when the response is not successful (best effort).
    * - Never equal when there's other exceptions (unless those define equality, which they typically don't).
    */
  private def keyToGroupResponses[T](
      r1: Try[T]
  ): Future[ScanResponse[T]] = {
    r1 match {
      case Success(value) => Future.successful(SuccessfulResponse(value))
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpNonJsonResponse) =>
        Future.successful(NonJsonHttpFailureResponse(unexpected.statusCode))
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpTextResponse) =>
        Future.successful(
          TextFailureResponse(unexpected.statusCode, unexpected.content)
        )
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpJsonResponse) =>
        Future.successful(
          HttpFailureResponse(unexpected.statusCode, unexpected.content)
        )
      case Failure(unexpected: HttpCommandException) =>
        Future.successful(
          HttpFailureResponse(
            unexpected.status,
            Json.obj("message" -> Json.fromString(unexpected.message)),
          )
        )
      case Failure(error) =>
        Future.successful(ExceptionFailureResponse(error))
    }
  }

  private def logDisagreements[T](
      logger: TracedLogger,
      consensusResponse: Try[T],
      responses: ConcurrentHashMap[ScanResponse[T], List[Uri]],
      disagreementLogLevel: Level,
      connectionMetrics: Option[ScanConnectionMetrics],
  )(implicit ec: ExecutionContext, tc: TraceContext, mc: MetricsContext): Unit = {
    implicit val elc: ErrorLoggingContext = ErrorLoggingContext.fromTracedLogger(logger)
    def recordConsensus(url: Uri, consensus: String, extraLabels: Map[String, String]): Unit =
      connectionMetrics.foreach { metrics =>
        val context = mc.merge(
          MetricsContext(
            Map(
              "scan_connection" -> url.authority.host.address(),
              "consensus" -> consensus,
            ) ++ extraLabels
          )
        )
        metrics.bftPerConnectionConsensus.mark()(context)
      }
    def disagreementLabels(response: ScanResponse[T]): Map[String, String] =
      response match {
        case _: SuccessfulResponse[?] => Map("success" -> "true")
        case HttpFailureResponse(status, _) =>
          Map("success" -> "false", "http_status" -> status.intValue.toString)
        case NonJsonHttpFailureResponse(status) =>
          Map("success" -> "false", "http_status" -> status.intValue.toString)
        case TextFailureResponse(status, _) =>
          Map("success" -> "false", "http_status" -> status.intValue.toString)
        case _: ExceptionFailureResponse[?] => Map("success" -> "false")
      }
    keyToGroupResponses(consensusResponse).foreach { consensusResponseKey =>
      val agreeingScanUrls = responses.remove(consensusResponseKey)
      agreeingScanUrls.foreach(recordConsensus(_, "agree", Map.empty))
      responses.forEach { (disagreeingResponse, scanUrls) =>
        val extraLabels = disagreementLabels(disagreeingResponse)
        scanUrls.foreach(recordConsensus(_, "disagree", extraLabels))
        LoggerUtil.logAtLevel(
          disagreementLogLevel,
          s"""The following Scan URLs disagreed with consensus:
             |${scanUrls.map(url => s"  $url").mkString("\n")}
             |consensus response: $consensusResponse
             |disagreeing response: $disagreeingResponse""".stripMargin,
        )
      }
    }
  }

  private sealed trait ScanResponse[+T]
  private case class SuccessfulResponse[+T](response: T) extends ScanResponse[T]
  private case class HttpFailureResponse[+T](status: StatusCode, body: Json) extends ScanResponse[T]
  private case class NonJsonHttpFailureResponse[+T](status: StatusCode) extends ScanResponse[T]
  private case class TextFailureResponse[+T](status: StatusCode, content: String)
      extends ScanResponse[T]
  private case class ExceptionFailureResponse[+T](error: Throwable) extends ScanResponse[T]

  class ConsensusNotReached(
      numRequests: Int,
      responses: Seq[(List[Uri], ScanResponse[?])],
  ) extends RuntimeException(
        s"Failed to reach consensus from $numRequests Scan nodes. Responses: $responses"
      )
  object ConsensusNotReached {
    def apply[T](
        numRequests: Int,
        responses: ConcurrentHashMap[ScanResponse[T], List[Uri]],
        shortenResponses: T => Any,
    ): ConsensusNotReached = {
      val shortResponses: Seq[(List[Uri], ScanResponse[?])] =
        responses.asScala.toSeq.map {
          case (SuccessfulResponse(response), uris) =>
            uris -> SuccessfulResponse(shortenResponses(response))
          case (HttpFailureResponse(status, body), uris) =>
            uris -> HttpFailureResponse(status, body)
          case (NonJsonHttpFailureResponse(status), uris) =>
            uris -> NonJsonHttpFailureResponse(status)
          case (TextFailureResponse(status, body), uris) =>
            uris -> TextFailureResponse(status, body)
          case (ExceptionFailureResponse(error), uris) => uris -> ExceptionFailureResponse(error)
        }

      new ConsensusNotReached(numRequests, shortResponses)
    }
  }

  object ConsensusNotReachedRetryable extends ExceptionRetryPolicy {
    override def determineExceptionErrorKind(exception: Throwable, logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = {
      exception match {
        case c: ConsensusNotReached =>
          logger.info("Consensus not reached. Will be retried.", c)
          ErrorKind.TransientErrorKind()
        case _ => ErrorKind.FatalErrorKind
      }
    }
  }

  case class MigrationInfoResponses(
      withData: Map[SingleScanConnection, SourceMigrationInfo],
      withoutData: Set[SingleScanConnection],
      unknownStatus: Set[SingleScanConnection],
  )

  /** getMigrationInfo is used for backfilling Scan data as part of SV onboarding.
   * It has its own unique BFT logic, to prevent multiple SVs onboarding in parallel
   * from live-locking each other by all not having the data thus breaking BFT */

  def getMigrationInfo(
      connections: ScanConnections,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      migrationId: Long,
  )(implicit
      loggingContext: com.digitalasset.canton.logging.ErrorLoggingContext,
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[SourceMigrationInfo]] = {
    for {
      // Ask ALL scans for the migration info
      responses <- getMigrationInfoResponses(connections, migrationId)
      result <-
        if (responses.withData.nonEmpty) {
          // At least one scan reported to have some data for the given migration id
          val completeResponses = responses.withData.filter { case (_, migrationInfo) =>
            migrationInfo.complete
          }
          val importUpdatesCompleteResponses = responses.withData.filter {
            case (_, migrationInfo) =>
              migrationInfo.importUpdatesComplete
          }
          for {
            // We already have the responses, use bftCall() to avoid re-implementing the consensus logic.
            // All non-malicious scans that have backfilled the input migrationId should return
            // the same value for previousMigrationId.
            previousMigrationId <- bftCallWithScanUris(
              connections,
              connectionMetrics,
              retryProvider,
              logger,
              connection => Future.successful(completeResponses(connection).previousMigrationId),
              "getMigrationInfo",
              BftCallConfig.forAvailableData(connections, completeResponses.contains),
              // This method is very sensitive to unavailable SVs.
              // Do not log warnings for failures to reach consensus, as this would be too noisy,
              // and instead rely on metrics to situations when backfilling is not progressing.
              Level.INFO,
            ).map(_._1)
            lastImportUpdateId <- bftCallWithScanUris(
              connections,
              connectionMetrics,
              retryProvider,
              logger,
              connection =>
                Future.successful(importUpdatesCompleteResponses(connection).lastImportUpdateId),
              "getMigrationInfo",
              BftCallConfig.forAvailableData(connections, importUpdatesCompleteResponses.contains),
              // This method is very sensitive to unavailable SVs.
              // Do not log warnings for failures to reach consensus, as this would be too noisy,
              // and instead rely on metrics to situations when backfilling is not progressing.
              Level.INFO,
            ).map(_._1)
          } yield {
            @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
            val unionOfRecordTimeRanges =
              responses.withData.values.map(_.recordTimeRange).reduce(_ |+| _)
            Some(
              SourceMigrationInfo(
                previousMigrationId = previousMigrationId,
                recordTimeRange = unionOfRecordTimeRanges,
                lastImportUpdateId = lastImportUpdateId,
                complete = completeResponses.nonEmpty,
                importUpdatesComplete = importUpdatesCompleteResponses.nonEmpty,
              )
            )
          }
        } else if (responses.withoutData.nonEmpty) {
          // All scans reported to have no data for the given migration id
          logger.info(
            s"All ${responses.withoutData.size} available scans reported to have no data for migration ${migrationId}"
          )
          Future.successful(None)
        } else {
          // No valid response from any scan
          val httpError =
            HttpErrorWithHttpCode(
              StatusCodes.BadGateway,
              s"No valid response from any scan.",
            )
          Future.failed(httpError)
        }
    } yield result
  }

  def getMigrationInfoResponses(connections: ScanConnections, migrationId: Long)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[MigrationInfoResponses] = for {
    results <- Future.traverse(connections.open)(connection =>
      connection
        .getMigrationInfo(migrationId)
        .transformWith(keyToGroupResponses)
        .map(result => connection -> result)
    )
  } yield {
    val (withData, other) =
      results.partitionMap { case (connection, response) =>
        response match {
          case SuccessfulResponse(Some(info)) =>
            Left(connection -> info)
          case SuccessfulResponse(None) =>
            Right(Left(connection))
          case _: HttpFailureResponse[?] | _: NonJsonHttpFailureResponse[?] |
              _: TextFailureResponse[?] | _: ExceptionFailureResponse[?] =>
            Right(Right(connection))
        }
      }
    val (withoutData, unknownStatus) = other partitionMap identity
    MigrationInfoResponses(
      withData.toMap,
      withoutData.toSet,
      unknownStatus.toSet,
    )
  }
}

trait HasUrl {
  def url: Uri
}

