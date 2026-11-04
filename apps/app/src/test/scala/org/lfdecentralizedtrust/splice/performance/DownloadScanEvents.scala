package org.lfdecentralizedtrust.splice.performance

import cats.data.Chain
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.{ActorSystem, Scheduler}
import org.lfdecentralizedtrust.splice.http.{HttpClient, HttpClientMetrics}
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  DamlValueEncoding,
  EventHistoryItem,
  EventHistoryResponse,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.GetEventHistory

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class DownloadScanEvents(
    host: String,
    migrationId: Int,
    writePath: Path,
    startAt: Instant,
    duration: Duration,
)(implicit
    ec: ExecutionContext,
    as: ActorSystem,
    override val loggerFactory: NamedLoggerFactory,
    tc: TraceContext,
    metricsFactory: LabeledMetricsFactory,
) extends Runnable
    with NamedLogging {

  val until: Instant = startAt.plusNanos(duration.toNanos)

  def run(): Unit = {
    implicit val httpClient: HttpClient = {
      HttpClient(
        HttpClient.HttpRequestParameters(NonNegativeDuration.ofSeconds(40L)),
        HttpClientMetrics(metricsFactory),
        logger,
      )
    }
    implicit val scheduler: Scheduler = as.scheduler

    def query(at: Instant): Future[EventHistoryResponse] = {
      org.apache.pekko.pattern
        .retry(
          () => {
            logger.info(s"Querying at $at")
            val command = GetEventHistory(
              count = 1000,
              after = Some((migrationId.toLong, at.toString)),
              DamlValueEncoding.CompactJson,
            )
            command
              .submitRequest(command.createClient(host), Nil)
              .value
              .map(
                _.fold(
                  error => {
                    val exception = new RuntimeException(error.toString)
                    logger.error(s"Failed to get events at $at.", exception)
                    throw exception
                  },
                  _.fold(
                    identity,
                    error => throw new RuntimeException(error.error),
                    error => throw new RuntimeException(error.error),
                  ),
                )
              )
          },
          attempts = 10,
          delay = 10.seconds,
        )
    }

    @tailrec
    def loop(at: Instant, acc: Chain[EventHistoryItem]): Chain[EventHistoryItem] = {
      val nextResponse = Await.result(query(at), atMost = 2.minutes)
      val (pastTime, toInclude) =
        nextResponse.events.partition(event => getRecordTime(event).isAfter(until))
      val newAcc = acc ++ Chain.fromSeq(toInclude)
      toInclude.lastOption match {
        case Some(last) if pastTime.isEmpty => loop(getRecordTime(last), newAcc)
        case _ => newAcc
      }
    }

    val eventsInDuration = loop(startAt, Chain.empty)
    logger.info(s"Finished downloading events, total events: ${eventsInDuration.size}.")
    val toWrite = EventHistoryResponse.encodeEventHistoryResponse(
      EventHistoryResponse(eventsInDuration.toVector)
    )
    Files.write(writePath, toWrite.noSpaces.getBytes(StandardCharsets.UTF_8))
    logger.info(s"Successfully written events to $writePath.")
  }

  // An event carries a verdict, an update, or both; both share the same record time.
  private def getRecordTime(event: EventHistoryItem): Instant =
    Instant.parse(
      event.verdict
        .map(_.recordTime)
        .orElse(event.update.map {
          case members.UpdateHistoryTransactionV2(value) => value.recordTime
          case members.UpdateHistoryReassignment(value) => value.recordTime
        })
        .getOrElse(throw new IllegalStateException("Event has neither a verdict nor an update."))
    )

}

