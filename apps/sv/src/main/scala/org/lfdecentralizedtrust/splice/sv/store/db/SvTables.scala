// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns
import org.lfdecentralizedtrust.splice.store.db.{
  AcsRowData,
  AcsTables,
  HashIndexedTextColumn,
  IndexColumnValue,
}
import org.lfdecentralizedtrust.splice.util.Contract

object SvTables extends AcsTables {

  /** Index columns holding free text, which are indexed by a hash of their value. */
  object HashIndexedColumns {
    val onboardingSecret: HashIndexedTextColumn = HashIndexedTextColumn("onboarding_secret")
  }

  case class SvAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      onboardingSecret: Option[String] = None,
      svCandidateName: Option[String] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      SvAcsStoreRowData.IndexColumns.onboarding_secret -> onboardingSecret,
      SvAcsStoreRowData.IndexColumns.sv_candidate_name -> svCandidateName,
    )
  }

  object SvAcsStoreRowData {
    implicit val hasIndexColumns: HasIndexColumns[SvAcsStoreRowData] =
      new HasIndexColumns[SvAcsStoreRowData] {
        override def indexColumnNames: Seq[String] = IndexColumns.All
      }
    private object IndexColumns {
      val onboarding_secret = "onboarding_secret"
      val sv_candidate_name = "sv_candidate_name"
      val All = Seq(onboarding_secret, sv_candidate_name)
    }
  }

}
