// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.config.CantonRequireTypes.String2066
import com.digitalasset.canton.topology.PartyId
import slick.jdbc.*

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.sql.JDBCType

trait JdbcTypes {

  val profile: slick.jdbc.JdbcProfile
  import profile.api.*

  /** The DB may truncate strings of unbounded length, so it's advised to use a LengthLimitedString instead.
    * We use String2066 because it's the max length of an [[com.digitalasset.canton.protocol.LfTemplateId]].
    * Truncates instead of failing, as ingestion must not stop on an over-long value. The bound is in
    * UTF-8 bytes rather than characters because these values are indexed, and Postgres rejects btree
    * entries above 2704 bytes.
    */
  protected def lengthLimited(s: String): String2066 =
    String2066.createWithTruncation(truncateToMaxIndexBytes(s))

  /** Kept well below the 2704 bytes that Postgres allows per btree entry, so that the other
    * columns of the composite indexes these values appear in also fit. Values that reach these
    * columns are bounded on the ledger by DsoRulesConfig.maxTextLength, which is far below this;
    * raising maxTextLength above it would turn exact-match lookups into prefix matches.
    */
  private val maxIndexColumnBytes = 2000

  private def truncateToMaxIndexBytes(s: String, maxBytes: Int = maxIndexColumnBytes): String = {
    // UTF-8 uses at most 3 bytes per UTF-16 unit, so this avoids encoding all but huge values.
    if (s.length <= maxBytes / 3) s
    else {
      val bytes = s.getBytes(StandardCharsets.UTF_8)
      if (bytes.length <= maxBytes) s
      else
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.IGNORE)
          .onUnmappableCharacter(CodingErrorAction.IGNORE)
          .decode(ByteBuffer.wrap(bytes, 0, maxBytes))
          .toString
    }
  }

  /** A `LIKE` pattern matching the values that contain `s`. Room is left for the wildcards, so
    * that truncating an over-long value cannot drop them and silently turn the pattern into an
    * ends-with match.
    */
  protected def lengthLimitedContainsPattern(s: String): String2066 =
    String2066.createWithTruncation(s"%${truncateToMaxIndexBytes(s, maxIndexColumnBytes - 2)}%")

  protected implicit def partyIdGetResult[T]: GetResult[PartyId] =
    GetResult.GetString.andThen(PartyId.tryFromProtoPrimitive)

  protected implicit def partyIdGetResultOption[T]: GetResult[Option[PartyId]] =
    GetResult.GetStringOption.andThen(_.map(PartyId.tryFromProtoPrimitive))

  protected implicit lazy val partyIdJdbcType: JdbcType[PartyId] =
    MappedColumnType.base[PartyId, String](_.toProtoPrimitive, PartyId.tryFromProtoPrimitive)

  protected implicit lazy val partyIdSetParameterOption: SetParameter[Option[PartyId]] =
    (partyId: Option[PartyId], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String2066]]]
        .apply(partyId.map(party => lengthLimited(party.toProtoPrimitive)), pp)

  protected implicit lazy val partyIdSetParameterArray: SetParameter[Array[PartyId]] =
    (partyId: Array[PartyId], pp: PositionedParameters) =>
      implicitly[SetParameter[Array[String2066]]]
        .apply(partyId.map(party => lengthLimited(party.toProtoPrimitive)), pp)

  protected implicit lazy val stringArraySetParameter: SetParameter[Array[String]] =
    (strings: Array[String], pp: PositionedParameters) =>
      pp.setObject(
        pp.ps.getConnection.createArrayOf("text", strings.map(x => x)),
        JDBCType.ARRAY.getVendorTypeNumber,
      )

  protected implicit lazy val string2066ArraySetParameter: SetParameter[Array[String2066]] =
    (strings: Array[String2066], pp: PositionedParameters) =>
      stringArraySetParameter(strings.map(_.str), pp)

}
