// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data as javaApi
import io.circe.Json
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  RewardConfig,
  RewardVersion,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulettransferinstruction.AmuletTransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  round as roundCodegen,
  types as typesCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense_Withdraw
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.scalatest.EitherValues

import java.time.Instant
import java.util.Optional
import scala.jdk.CollectionConverters.*

class ValueJsonCodecTranscodeTest extends StoreTestBase with EitherValues {

  "ValueJsonCodecTranscode" should {
    val validator = mkPartyId("validator")
    val sponsor = mkPartyId("sponsor")
    val licenseContract = validatorLicense(validator, sponsor)
    val licenseFields = licenseContract.payload.toValue.getFields.asScala.toList.map(_.getValue)
    val partyFields = licenseFields.take(3)
    val none = javaApi.DamlOptional.EMPTY

    def parse(json: String): Json = io.circe.parser.parse(json).value
    def serializeFields(fields: Seq[javaApi.Value]): Json = {
      val e = toCreatedEvent(licenseContract)
      val event = new javaApi.CreatedEvent(
        e.getWitnessParties,
        e.getOffset,
        e.getNodeId,
        e.getTemplateId,
        e.getPackageName,
        e.getContractId,
        new javaApi.DamlRecord(fields.map(new javaApi.DamlRecord.Field(_)).asJava),
        e.getCreatedEventBlob,
        e.getInterfaceViews,
        e.getFailedInterfaceViews,
        e.getContractKey,
        e.getSignatories,
        e.getObservers,
        e.getCreatedAt,
        e.isAcsDelta,
        e.getRepresentativePackageId,
      )
      parse(ValueJsonCodecTranscode.serializableContractPayload(event).value)
    }

    // ValidatorLicense ends with the optional fields faucetState, metadata and lastActiveAt.
    // An older version of the template that does not have them (or a participant that omits
    // trailing None fields) produces records with fewer fields.
    val partyFieldsJson = Json.obj(
      "validator" -> Json.fromString(validator.toProtoPrimitive),
      "sponsor" -> Json.fromString(sponsor.toProtoPrimitive),
      "dso" -> Json.fromString(dsoParty.toProtoPrimitive),
    )

    "serialize the same JSON for records with and without trailing None fields" in {
      serializeFields(partyFields ++ Seq(none, none, none)) shouldBe partyFieldsJson
      serializeFields(partyFields) shouldBe partyFieldsJson
    }

    // The following tests pin the exact output of the codec, to notice if an upgrade of the transcode
    // library changes the encoding of any value, and compare it with ValueJsonCodecCodegen (compact_json).
    val rewardConfig = new RewardConfig(
      RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS,
      Optional.empty(), // dryRunVersion, a null that is not trailing
      100L,
      new RelTime(36L * 3600L * 1000000L),
      new java.math.BigDecimal("0.5"),
      Optional.of(new java.math.BigDecimal("0.2")),
      Optional.empty(),
      Optional.empty(),
    )
    // Covers Party, Int64, Numeric, Timestamp, enums, lists, optionals, nested records and contract ids
    val roundEvent = toCreatedEvent(
      openMiningRound(
        dsoParty,
        42,
        0.5,
        Instant.parse("2026-01-02T03:04:05.123456Z"),
        validContractId(9),
        Optional.of(rewardConfig),
      )
    )
    val tapEvent = exercisedEvent(
      contractId = validContractId(3),
      templateId = amuletrulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
      interfaceId = None,
      choice = amuletrulesCodegen.AmuletRules.CHOICE_AmuletRules_DevNet_Tap.name,
      consuming = false,
      argument = new amuletrulesCodegen.AmuletRules_DevNet_Tap(
        dsoParty.toProtoPrimitive,
        BigDecimal(13).bigDecimal,
        new roundCodegen.OpenMiningRound.ContractId(validContractId(1)),
      ).toValue,
      result = new amuletrulesCodegen.AmuletRules_DevNet_TapResult(
        new amuletCodegen.AmuletCreateSummary(
          new amuletCodegen.Amulet.ContractId(validContractId(2)),
          BigDecimal(0.5).bigDecimal,
          new typesCodegen.Round(31L),
        ),
        Optional.empty(),
      ).toValue,
    )

    "produce the expected JSON strings" in {
      ValueJsonCodecTranscode.serializableContractPayload(roundEvent).value shouldBe
        """{"dso":"dso::dummy","round":{"number":"42"},"amuletPrice":"0.5000000000",""" +
        """"opensAt":"2026-01-02T03:04:05.123456Z","targetClosesAt":"2026-01-02T03:14:05.123456Z",""" +
        """"issuingFor":{"microseconds":"1000000"},"transferConfigUsd":{"createFee":{"fee":"0.0000000000"},""" +
        """"holdingFee":{"rate":"1.0000000000"},"transferFee":{"initialRate":"0.0000000000","steps":[]},""" +
        """"lockHolderFee":{"fee":"0.0000000000"},"extraFeaturedAppRewardAmount":"1.0000000000",""" +
        """"maxNumInputs":"10","maxNumOutputs":"100","maxNumLockHolders":"50"},""" +
        """"issuanceConfig":{"amuletToIssuePerYear":"10.0000000000","validatorRewardPercentage":"10.0000000000",""" +
        """"appRewardPercentage":"10.0000000000","validatorRewardCap":"0.2000000000",""" +
        """"featuredAppRewardCap":"100.0000000000","unfeaturedAppRewardCap":"0.6000000000",""" +
        """"optValidatorFaucetCap":"2.8500000000"},"tickDuration":{"microseconds":"1000000"},""" +
        """"trafficPrice":null,"rewardConfig":{"mintingVersion":"RewardVersion_TrafficBasedAppRewards",""" +
        """"dryRunVersion":null,"batchSize":"100","rewardCouponTimeToLive":{"microseconds":"129600000000"},""" +
        """"appRewardCouponThreshold":"0.5","extraFeaturedAssetActivityWeight":"0.2"}}"""
      ValueJsonCodecTranscode.serializeChoiceArgument(tapEvent).value shouldBe
        s"""{"receiver":"dso::dummy","amount":"13","openRound":"${validContractId(1)}"}"""
      ValueJsonCodecTranscode.serializeChoiceResult(tapEvent).value shouldBe
        s"""{"amuletSum":{"amulet":"${validContractId(
            2
          )}","amuletPrice":"0.5","round":{"number":"31"}}}"""
    }

    // Differences to ValueJsonCodecCodegen that are not about the formatting of the JSON:
    // - trailing null fields are omitted
    // - numerics with value zero are written as "0.0000000000" instead of "0E-10"
    // Everything else, in particular Int64 and non-zero Numeric values, is encoded the same way.
    def dropTrailingNulls(json: Json): Json = json.fold(
      json,
      _ => json,
      _ => json,
      _ => json,
      arr => Json.fromValues(arr.map(dropTrailingNulls)),
      obj =>
        Json.fromFields(
          obj.toList
            .map { case (k, v) => k -> dropTrailingNulls(v) }
            .reverse
            .dropWhile { case (_, v) => v.isNull }
            .reverse
        ),
    )
    def plainZero(json: Json): Json = json.fold(
      json,
      _ => json,
      _ => json,
      s => Json.fromString(if (s == "0E-10") "0.0000000000" else s),
      arr => Json.fromValues(arr.map(plainZero)),
      obj => Json.fromFields(obj.toList.map { case (k, v) => k -> plainZero(v) }),
    )
    def normalizedCodegen(json: String): Json = plainZero(dropTrailingNulls(parse(json)))

    "encode values like ValueJsonCodecCodegen, except for trailing nulls and zero numerics" in {
      parse(ValueJsonCodecTranscode.serializableContractPayload(roundEvent).value) shouldBe
        normalizedCodegen(ValueJsonCodecCodegen.serializableContractPayload(roundEvent).value)
      parse(ValueJsonCodecTranscode.serializeChoiceArgument(tapEvent).value) shouldBe
        normalizedCodegen(ValueJsonCodecCodegen.serializeChoiceArgument(tapEvent).value)
      parse(ValueJsonCodecTranscode.serializeChoiceResult(tapEvent).value) shouldBe
        normalizedCodegen(ValueJsonCodecCodegen.serializeChoiceResult(tapEvent).value)
    }

    "encode zero numerics differently from ValueJsonCodecCodegen" in {
      val fee = (json: Json) =>
        json.hcursor
          .downField("transferConfigUsd")
          .downField("createFee")
          .downField("fee")
          .as[String]
      fee(parse(ValueJsonCodecCodegen.serializableContractPayload(roundEvent).value)) shouldBe
        Right("0E-10")
      fee(parse(ValueJsonCodecTranscode.serializableContractPayload(roundEvent).value)) shouldBe
        Right("0.0000000000")
    }

    "serialize values of all bundled versions of a package, using the latest version" in {
      // Relies on the upgrade guarantee of Daml, see the comment of ValueJsonCodecTranscode
      val versions = DarResources.amulet.all
      versions.size should be > 1
      val template = licenseContract.identifier
      val e = toCreatedEvent(licenseContract)
      val results = versions.map { version =>
        val event = new javaApi.CreatedEvent(
          e.getWitnessParties,
          e.getOffset,
          e.getNodeId,
          new javaApi.Identifier(version.packageId, template.getModuleName, template.getEntityName),
          e.getPackageName,
          e.getContractId,
          new javaApi.DamlRecord(partyFields.map(new javaApi.DamlRecord.Field(_)).asJava),
          e.getCreatedEventBlob,
          e.getInterfaceViews,
          e.getFailedInterfaceViews,
          e.getContractKey,
          e.getSignatories,
          e.getObservers,
          e.getCreatedAt,
          e.isAcsDelta,
          version.packageId,
        )
        version.metadata.version.toString ->
          ValueJsonCodecTranscode.serializableContractPayload(event).map(parse)
      }
      results shouldBe versions.map(v => v.metadata.version.toString -> Right(partyFieldsJson))
    }

    "keep null fields that are followed by a non-null field" in {
      val json = serializeFields(partyFields ++ Seq(none, licenseFields(4), none))
      json.hcursor.downField("faucetState").focus shouldBe Some(Json.Null)
      json.hcursor.downField("metadata").focus.exists(!_.isNull) shouldBe true
      json.hcursor.downField("lastActiveAt").focus shouldBe None
    }

    "serialize all fields of a complete record" in {
      val json = parse(
        ValueJsonCodecTranscode
          .serializableContractPayload(toCreatedEvent(licenseContract))
          .value
      )
      json.hcursor.downField("metadata").downField("version").as[String] shouldBe Right("0.1.0")
      json.hcursor.downField("lastActiveAt").focus.exists(!_.isNull) shouldBe true
    }

    "deserialize the output to the same contract" in {
      val event = toCreatedEvent(licenseContract)
      val json = ValueJsonCodecTranscode.serializableContractPayload(event).value
      val roundTripped =
        ValueJsonCodecTranscode.deserializableContractPayload(event.getTemplateId, json).value
      // Decoded values do not have field labels, so compare by serializing again
      serializeFields(roundTripped.getFields.asScala.toList.map(_.getValue)) shouldBe parse(json)
      roundTripped.getFields.asScala.toList.map(_.getValue).size shouldBe licenseFields.size

      // JSON without the trailing fields decodes to a record with fewer fields, as created by an older version
      val jsonWithoutNones = serializeFields(partyFields).noSpaces
      val decoded = ValueJsonCodecTranscode
        .deserializableContractPayload(event.getTemplateId, jsonWithoutNones)
        .value
      decoded.getFields.asScala.toList.map(_.getValue) shouldBe partyFields
    }

    "serialize and deserialize choice arguments and results" in {
      val arg = new ValidatorLicense_Withdraw("because")
      // ValidatorLicense_WithdrawResult has no fields, so Daml-LF represents it as an enum
      val result = new javaApi.DamlEnum("ValidatorLicense_WithdrawResult")
      val event = exercisedEvent(
        licenseContract.contractId.contractId,
        licenseContract.identifier,
        None,
        "ValidatorLicense_Withdraw",
        consuming = false,
        arg.toValue,
        result,
      )
      val argJson = ValueJsonCodecTranscode.serializeChoiceArgument(event).value
      parse(argJson) shouldBe Json.obj("reason" -> Json.fromString("because"))
      val decodedArg = ValueJsonCodecTranscode
        .deserializeChoiceArgument(event.getTemplateId, None, event.getChoice, argJson)
        .value
      val resultJson = ValueJsonCodecTranscode.serializeChoiceResult(event).value
      val decodedResult = ValueJsonCodecTranscode
        .deserializeChoiceResult(event.getTemplateId, None, event.getChoice, resultJson)
        .value
      decodedResult shouldBe result
      val event2 = exercisedEvent(
        licenseContract.contractId.contractId,
        licenseContract.identifier,
        None,
        "ValidatorLicense_Withdraw",
        consuming = false,
        decodedArg,
        decodedResult,
      )
      ValueJsonCodecTranscode.serializeChoiceArgument(event2).value shouldBe argJson
    }

    "serialize and deserialize choices of interfaces" in {
      val arg = new transferinstructionv1.TransferInstruction_Withdraw(
        new metadatav1.ExtraArgs(
          new metadatav1.ChoiceContext(java.util.Map.of()),
          new metadatav1.Metadata(java.util.Map.of("k", "v")),
        )
      )
      val result = new transferinstructionv1.TransferInstructionResult(
        new transferinstructionv1.transferinstructionresult_output.TransferInstructionResult_Failed(
          javaApi.Unit.getInstance()
        ),
        java.util.List.of(),
        new metadatav1.Metadata(java.util.Map.of()),
      )
      val interfaceId = transferinstructionv1.TransferInstruction.INTERFACE_ID_WITH_PACKAGE_ID
      val event = exercisedEvent(
        nextCid(),
        AmuletTransferInstruction.TEMPLATE_ID_WITH_PACKAGE_ID,
        Some(interfaceId),
        "TransferInstruction_Withdraw",
        consuming = true,
        arg.toValue,
        result.toValue,
      )
      val argJson = ValueJsonCodecTranscode.serializeChoiceArgument(event).value
      parse(argJson).hcursor
        .downField("extraArgs")
        .downField("meta")
        .downField("values")
        .focus shouldBe Some(Json.obj("k" -> Json.fromString("v")))
      val decodedArg = ValueJsonCodecTranscode
        .deserializeChoiceArgument(event.getTemplateId, Some(interfaceId), event.getChoice, argJson)
        .value
      val resultJson = ValueJsonCodecTranscode.serializeChoiceResult(event).value
      val decodedResult = ValueJsonCodecTranscode
        .deserializeChoiceResult(
          event.getTemplateId,
          Some(interfaceId),
          event.getChoice,
          resultJson,
        )
        .value
      // Decoded values do not have field labels, so compare by serializing again
      val event2 = exercisedEvent(
        nextCid(),
        AmuletTransferInstruction.TEMPLATE_ID_WITH_PACKAGE_ID,
        Some(interfaceId),
        "TransferInstruction_Withdraw",
        consuming = true,
        decodedArg,
        decodedResult,
      )
      ValueJsonCodecTranscode.serializeChoiceArgument(event2).value shouldBe argJson
      ValueJsonCodecTranscode.serializeChoiceResult(event2).value shouldBe resultJson
    }

    "fail for values of unknown packages" in {
      val unknown = new javaApi.Identifier("0" * 64, "Mod", "Ent")
      ValueJsonCodecTranscode
        .deserializableContractPayload(unknown, "{}")
        .left
        .value should include("Unknown package")
    }
  }
}
