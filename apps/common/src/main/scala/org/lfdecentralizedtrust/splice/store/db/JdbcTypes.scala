// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.config.CantonRequireTypes.String2066
import com.digitalasset.canton.topology.PartyId
import slick.jdbc.*

import java.sql.JDBCType

trait JdbcTypes {

  val profile: slick.jdbc.JdbcProfile
  import profile.api.*

  /** The DB may truncate strings of unbounded length, so it's advised to use a LengthLimitedString instead.
    * We use String2066 because it's the max length of an [[com.digitalasset.canton.protocol.LfTemplateId]].
    */
  protected def lengthLimited(s: String): String2066 = String2066.tryCreate(s)

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
