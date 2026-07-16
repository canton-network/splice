package org.lfdecentralizedtrust.splice.scan.util

import com.digitalasset.canton.lifecycle.SyncCloseable
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerClient
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContextExecutor, Future, blocking}

trait HasPeerBftScanConnection {

  private val mutex = Mutex()

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var connectionVar: Option[BftScanConnection] = None

  protected def getOrCreateScanConnection(
      store: ScanStore,
      svName: String,
      ledgerClient: SpliceLedgerClient,
      context: TriggerContext,
      upgradesConfig: UpgradesConfig,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContextExecutor,
      mat: Materializer,
      httpClient: HttpClient,
      templateJsonDecoder: TemplateJsonDecoder,
  ): Future[BftScanConnection] =
    blocking {
      mutex.exclusive {
        connectionVar match {
          case Some(connection) =>
            Future.successful(connection)
          case None =>
            for {
              connection <- BftScanConnection.peerScanConnection(
                () => BftScanConnection.Bft.getPeerScansFromStore(store, svName),
                ledgerClient,
                // When the network is starting up, the pool of SVs is changing fast
                // Using a short refresh interval to quickly pick up new SVs
                scansRefreshInterval = context.config.pollingInterval,
                amuletRulesCacheTimeToLive = ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
                upgradesConfig,
                context.clock,
                context.retryProvider,
                loggerFactory,
              )
            } yield {
              connectionVar = Some(connection)
              connection
            }
        }
      }
    }

  protected def closeScanConnection(): Option[SyncCloseable] =
    connectionVar
      .map(connection =>
        SyncCloseable(
          "closing scan connection",
          connection.close(),
        )
      )

}
