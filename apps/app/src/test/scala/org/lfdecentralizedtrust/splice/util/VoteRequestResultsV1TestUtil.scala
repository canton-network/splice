// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CloseVoteRequestResult
import org.lfdecentralizedtrust.splice.console.ScanAppReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.VoteResultV1
import org.lfdecentralizedtrust.splice.store.VoteResultsFilters

import scala.annotation.tailrec

/** Compares the vote results served from the votes store (`/v1/admin/sv/voteresults`) with the ones
  * served from the txlog (`/v0/admin/sv/voteresults`).
  */
// TODO(#7728): remove the comparison once /v0/admin/sv/voteresults is served from the votes store as well
trait VoteRequestResultsV1TestUtil extends TestCommon {

  private val filtersToCompare = Seq(
    VoteResultsFilters(),
    VoteResultsFilters(accepted = Some(true)),
    VoteResultsFilters(accepted = Some(false)),
  )

  // Small enough to make the tests page through the results.
  private val comparisonPageSize = 2

  /** Checks that every scan serves the same vote results from the votes store as from its txlog,
    * and that all scans serve the same vote results from the votes store, in the same order.
    * Wrap in `eventually`, the stores ingest asynchronously.
    *
    * Returns the vote results of the first scan, for all filters.
    */
  def checkVoteRequestResultsV1(
      scans: Seq[ScanAppReference]
  ): Seq[VoteResultV1] = {
    scans.foreach { scan =>
      filtersToCompare.foreach { filters =>
        clue(
          s"${scan.name} serves the same vote results from the votes store and the txlog for $filters"
        ) {
          val fromVotesStore = listAllVoteRequestResultsV1(scan, filters)
          // Compare as a multiset: v0 orders ties by insertion order, and compares timestamps as text
          fromVotesStore.map(_.voteResult) should contain theSameElementsAs listAllTxLogVoteResults(
            scan,
            filters,
          )
          scan.countVoteRequestResultsV1(filters) shouldBe fromVotesStore.size.toLong
          scan.countVoteRequestResults(filters) shouldBe fromVotesStore.size.toLong
        }
      }
    }
    val results =
      scans.map(scan => scan.name -> listAllVoteRequestResultsV1(scan, VoteResultsFilters()))
    results.drop(1).foreach { case (name, result) =>
      clue(s"${scans.head.name} and $name serve the same vote results from the votes store") {
        result shouldBe results.head._2
      }
    }
    results.head._2
  }

  def listAllVoteRequestResultsV1(
      scan: ScanAppReference,
      filters: VoteResultsFilters,
  ): Seq[VoteResultV1] = {
    @tailrec
    def go(pageToken: Option[String], acc: Seq[VoteResultV1]): Seq[VoteResultV1] = {
      val (page, next) = scan.listVoteRequestResultsV1(filters, comparisonPageSize, pageToken)
      next match {
        case Some(_) => go(next, acc ++ page)
        case None => acc ++ page
      }
    }
    go(None, Seq.empty)
  }

  private def listAllTxLogVoteResults(
      scan: ScanAppReference,
      filters: VoteResultsFilters,
  ): Seq[DsoRules_CloseVoteRequestResult] = {
    @tailrec
    def go(
        pageToken: Option[BigInt],
        acc: Seq[DsoRules_CloseVoteRequestResult],
    ): Seq[DsoRules_CloseVoteRequestResult] = {
      val (page, next) = scan.listVoteRequestResults(filters, comparisonPageSize, pageToken)
      next match {
        case Some(_) => go(next, acc ++ page)
        case None => acc ++ page
      }
    }
    go(None, Seq.empty)
  }
}
