// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.daml.ledger.api.v2 as lapi
import com.daml.ledger.api.v2.state_service.GetActiveContractsResponse
import com.daml.ledger.api.v2.transaction_filter.EventFormat
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.apache.pekko.stream.{Attributes, RestartSettings}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection.ActiveContractsItem
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient
import org.lfdecentralizedtrust.splice.test.HasRetryProvider
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.event.Level

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.*

class ActiveContractsRestartTest
    extends BaseTest
    with AnyWordSpecLike
    with HasActorSystem
    with HasExecutionContext
    with HasRetryProvider {

  private val restartSettings: RestartSettings =
    RestartSettings(1.seconds, 10.second, 0.2)

  private def makeResponse(
      n: Int
  ): GetActiveContractsResponse =
    GetActiveContractsResponse(
      // We don't need to build the entire thing, only enough for test assertions
      contractEntry = GetActiveContractsResponse.ContractEntry.ActiveContract(
        com.daml.ledger.api.v2.state_service.ActiveContract.defaultInstance
          .withSynchronizerId("dummy::sync")
          .withCreatedEvent(
            com.daml.ledger.api.v2.event.CreatedEvent.defaultInstance
              .withContractId(n.toString)
              .withTemplateId(
                com.daml.ledger.api.v2.value.Identifier(
                  Amulet.PACKAGE_ID,
                  Amulet.TEMPLATE_ID_WITH_PACKAGE_ID.getModuleName,
                  Amulet.TEMPLATE_ID_WITH_PACKAGE_ID.getEntityName,
                )
              )
          )
      ),
      streamContinuationToken = ByteString.copyFromUtf8(s"token-$n"),
      workflowId = "whatever",
    )

  "activeContracts" should {
    "resume with the last continuation token after a stream Source failure" in {
      val callCount = new AtomicInteger(0)
      val ledgerClient = mock[LedgerClient]

      when(
        ledgerClient.activeContracts(any[lapi.state_service.GetActiveContractsRequest])(
          anyTraceContext
        )
      )
        .thenAnswer { (request: lapi.state_service.GetActiveContractsRequest) =>
          val call = callCount.incrementAndGet()
          call match {
            case 1 =>
              request.streamContinuationToken shouldBe None
              // return two items and then make it fail, which will cause the stream to restart
              Source(List(makeResponse(1), makeResponse(2), makeResponse(3)))
                .mapAsync(parallelism = 1) { item =>
                  if (
                    item.contractEntry.activeContract
                      .flatMap(_.createdEvent)
                      .exists(_.contractId == "3")
                  ) {
                    Future.failed(new RuntimeException("Stream failure on purpose"))
                  } else {
                    Future.successful(item)
                  }
                }
            case 2 =>
              // The continuation token is properly passed
              request.streamContinuationToken shouldBe Some(
                ByteString.copyFromUtf8("token-2")
              )
              Source.single(makeResponse(3))
          }
        }

      val connection = new BaseLedgerConnection(
        ledgerClient,
        "test-user",
        loggerFactory,
        testRetryProvider,
      )

      val result: Seq[BaseLedgerConnection.ActiveContractsItem] =
        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          connection
            .activeContracts(EventFormat.defaultInstance, 0L, restartSettings)
            // Disable akka's debugging which escapes the suppression
            .addAttributes(
              Attributes
                .logLevels(Attributes.logLevelOff, Attributes.logLevelOff, Attributes.logLevelOff)
            )
            .runWith(Sink.seq)
            .futureValue,
          lines => {
            forExactly(1, lines) { line =>
              line.message should include(
                "Starting active contracts stream with continuation token from last response: None"
              )
            }
            forExactly(1, lines) { line =>
              line.message should include(
                "Starting active contracts stream with continuation token from last response: Some"
              )
            }
          },
        )

      callCount.get() shouldBe 2
      result
        .collect { case ActiveContractsItem.ActiveContract(contract) =>
          contract.createdEvent.getContractId
        } should be(List("1", "2", "3"))
    }

    "fail the stream if the failure is not in the Source" in {
      val ledgerClient = mock[LedgerClient]
      when(
        ledgerClient.activeContracts(any[lapi.state_service.GetActiveContractsRequest])(
          anyTraceContext
        )
      )
        .thenAnswer { (_: lapi.state_service.GetActiveContractsRequest) =>
          Source.repeat(()).zipWithIndex.map { case (_, index) => makeResponse(index.toInt) }
        }

      val connection = new BaseLedgerConnection(
        ledgerClient,
        "test-user",
        loggerFactory,
        testRetryProvider,
      )

      val failure =
        new RuntimeException("Expected failure in the stream processing logic, not the Source")
      val result = connection
        .activeContracts(EventFormat.defaultInstance, 0L, restartSettings)
        .mapAsync(parallelism = 1)(_ => Future.failed(failure))
        .runWith(Sink.ignore)
        .failed
        .futureValue

      result should be(failure)
    }
  }
}
