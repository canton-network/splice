package org.lfdecentralizedtrust.splice.performance

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.performance.tests.{
  BaseStorePerformanceTest,
  ScanStoreIngestionPerformanceTest,
  StoreReadPerformanceTest,
  SvDsoStoreIngestionPerformanceTest,
  UpdateHistoryIngestionPerformanceTest,
  UpdateHistoryReadPerformanceTest,
  VerdictStoreIngestionPerformanceTest,
}

import java.nio.file.Path
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.NonFatal
import scala.concurrent.duration.DurationInt

class TestRunner(testNames: String, configPath: Path, dumpPath: Path)(implicit
    ec: ExecutionContext,
    actorSystem: ActorSystem,
    val loggerFactory: NamedLoggerFactory,
    tc: TraceContext,
) extends Runnable
    with NamedLogging {

  def run(): Unit = {
    val config: Config = ConfigFactory.parseFile(configPath.toFile).resolve()

    val ingestionTests: Map[String, () => BaseStorePerformanceTest] = Map(
      "DbSvDsoStore" -> (() =>
        SvDsoStoreIngestionPerformanceTest.tryCreate(dumpPath, config, loggerFactory)
      ),
      "DbScanStore" -> (() =>
        ScanStoreIngestionPerformanceTest
          .tryCreate(dumpPath, config, loggerFactory)
      ),
      "UpdateHistory" -> (() =>
        UpdateHistoryIngestionPerformanceTest.tryCreate(
          dumpPath,
          config,
          loggerFactory,
        )
      ),
      // expects an events dump
      "VerdictStore" -> (() =>
        VerdictStoreIngestionPerformanceTest.tryCreate(dumpPath, config, loggerFactory)
      ),
    )

    val readTests: Map[String, () => StoreReadPerformanceTest] = Map(
      "UpdateHistoryRead" -> (() =>
        UpdateHistoryReadPerformanceTest
          .tryCreate(dumpPath, config, loggerFactory)
      )
    )

    val regex = testNames.replace("*", ".*").r

    val selectedIngestionTests = ingestionTests.filter { case (name, _) => regex.matches(name) }
    val selectedReadTests = readTests.filter { case (name, _) => regex.matches(name) }

    def runTest(testName: String, runFuture: => scala.concurrent.Future[Unit]): Unit = {
      try {
        Await.result(runFuture, 1.hour)
        logger.info(s"Test '$testName' PASSED")
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Test '$testName' FAILED", ex)
          throw ex
      }
    }

    selectedIngestionTests.foreach { case (testName, testBuilder) =>
      runTest(testName, testBuilder().run())
    }

    selectedReadTests.foreach { case (testName, testBuilder) =>
      runTest(testName, testBuilder().run())
    }
  }

}
