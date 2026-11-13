// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.either.*
import com.daml.ledger.api.v2.value as scalaValue
import com.daml.ledger.javaapi.data as JavaApi
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Identifier}
import com.digitalasset.daml.lf.archive.{Decode, DarParser}
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.language.{Ast, Util as LfUtil}
import com.digitalasset.transcode.codec.json.JsonCodec
import com.digitalasset.transcode.codec.proto.ProtobufCodec
import com.digitalasset.transcode.daml_lf.LfSchemaProcessor
import com.digitalasset.transcode.schema.{Dictionary, IdentifierFilter, SchemaVisitor}
import com.digitalasset.transcode.{Converter, schema}
import org.lfdecentralizedtrust.splice.environment.DarResources

import java.util.zip.ZipInputStream
import scala.jdk.OptionConverters.*
import scala.util.{Try, Using}

/** A codec for converting [[com.daml.ledger.javaapi.data.Value]] to/from JSON strings in the format of the
  * JSON Ledger API (the format that the participant's JSON API v2 uses for contract payloads, choice arguments
  * and exercise results), using the transcode library that Canton uses for it.
  *
  * Unlike [[ValueJsonCodecCodegen]], the conversion is driven by the Daml-LF types of the bundled DARs,
  * not by the Java codegen. Trailing `None` fields of records are omitted.
  * As Daml upgrades can add optional fields at the end of a record, this makes the JSON of a value
  * independent of the version of the app that encodes it.
  *
  * NOTE: This depends on the upgrade guarantee of Daml. Only the latest version of each bundled package
  * is loaded ([[DarResources.packageResources]]), so that memory usage and startup time do not grow with
  * every package version that we release. Values created by any older version of a package are converted
  * using the types of the latest version, matching packages by package name. This works because upgrades
  * of a Daml package are backwards compatible: a value of an older version is also a valid value of the
  * latest version (records only gain optional fields at the end, variants and enums only gain constructors,
  * and templates and choices are not removed). If we ever ship a package version that breaks this, values
  * of its older versions fail to convert, and the conversions return a `Left`.
  *
  * Values of packages that are not bundled with the app can not be converted, and the conversions
  * return a `Left` for them. This is not an issue for Scan, as it is upgraded before new packages are vetted.
  *
  * Decoding JSON that has no trailing optional fields gives a record with fewer fields, like a
  * value created by an older version of the package.
  */
object ValueJsonCodecTranscode {

  private type JsonDict = Dictionary[Converter[scalaValue.Value, ujson.Value]]
  private type ProtoDict = Dictionary[Converter[ujson.Value, scalaValue.Value]]

  private lazy val allPackages = DarResources.packageResources.flatMap(_.all).distinctBy(_.path)
  private lazy val latestPackages = DarResources.packageResources.map(_.latest)

  // Name and version of every bundled version of every package, used to find the package name of a value
  private lazy val packageMetadata: Map[Ref.PackageId, Ast.PackageMetadata] =
    allPackages.map(r => Ref.PackageId.assertFromString(r.packageId) -> r.metadata).toMap

  // Of the latest versions of the bundled packages, and their dependencies (e.g. daml-stdlib).
  // Packages are shared between many DARs, so they are decoded only once per package id.
  private lazy val signatures: Map[Ref.PackageId, Ast.PackageSignature] =
    latestPackages
      .flatMap { resource =>
        val stream = getClass.getClassLoader.getResourceAsStream(resource.path)
        if (stream == null) throw new IllegalArgumentException(s"Not found: ${resource.path}")
        Using.resource(new ZipInputStream(stream)) { zip =>
          DarParser
            .readArchive(resource.path, zip)
            .valueOr(err =>
              throw new IllegalArgumentException(s"Failed to read DAR ${resource.path}: $err")
            )
            .all
        }
      }
      .distinctBy(_.getHash)
      .map { archive =>
        val (pkgId, pkg) = Decode
          .decodeArchive(archive)
          .valueOr(err =>
            throw new IllegalArgumentException(s"Failed to decode package ${archive.getHash}: $err")
          )
        pkgId -> LfUtil.toSignature(pkg)
      }
      .toMap

  // Built once, because the set of bundled packages is fixed
  private lazy val dictionaries: (ProtoDict, JsonDict) = {
    val jsonCodec = new JsonCodec(removeTrailingNonesInRecords = true)
    val visitor = SchemaVisitor.compose(jsonCodec, ProtobufCodec)
    // Only the entities of the latest versions are included. Their dependencies, which can be older
    // versions of other bundled packages, are only used to resolve types.
    val latestIds = latestPackages.map(r => Ref.PackageId.assertFromString(r.packageId))
    val (jsonDic, protoDic) = LfSchemaProcessor
      .process(latestIds, signatures, IdentifierFilter.AcceptAll)(visitor)
      .fold(error => throw new IllegalStateException(error), identity)
    val jsonByName = jsonDic.useStrictPackageMatching(false).matchByPackageName
    val protoByName = protoDic.useStrictPackageMatching(false).matchByPackageName
    (
      jsonByName.zipWith(protoByName)(Converter(_, _)),
      protoByName.zipWith(jsonByName)(Converter(_, _)),
    )
  }

  /** Loads the bundled packages and builds the dictionaries, which takes a few seconds.
    * Otherwise, this happens on first use. Blocks until done.
    */
  def initialize(): Unit = {
    val _ = dictionaries
  }

  private def protoDict: ProtoDict = dictionaries._1
  private def jsonDict: JsonDict = dictionaries._2

  def serializableContractPayload(
      event: CreatedEvent
  ): Either[String, String] =
    for {
      id <- schemaIdentifier(event.getTemplateId)
      converter <- jsonDict.getTemplate(id).toRight(s"Unknown template $id")
      json <- toJson(converter, event.getArguments)
    } yield json

  def deserializableContractPayload(
      templateId: Identifier,
      payload: String,
  ): Either[String, JavaApi.DamlRecord] =
    for {
      id <- schemaIdentifier(templateId)
      converter <- protoDict.getTemplate(id).toRight(s"Unknown template $id")
      value <- fromJson(converter, payload)
      record <- asRecord(value)
    } yield record

  def serializeChoiceArgument(
      event: ExercisedEvent
  ): Either[String, String] =
    for {
      id <- schemaIdentifier(event.getInterfaceId.toScala.getOrElse(event.getTemplateId))
      converter <- jsonDict
        .getChoiceArgument(id, schema.ChoiceName(event.getChoice))
        .toRight(s"Unknown choice ${event.getChoice} of $id")
      json <- toJson(converter, event.getChoiceArgument)
    } yield json

  def deserializeChoiceArgument(
      templateId: Identifier,
      interfaceId: Option[Identifier],
      choice: String,
      argument: String,
  ): Either[String, JavaApi.DamlRecord] =
    for {
      id <- schemaIdentifier(interfaceId.getOrElse(templateId))
      converter <- protoDict
        .getChoiceArgument(id, schema.ChoiceName(choice))
        .toRight(s"Unknown choice $choice of $id")
      value <- fromJson(converter, argument)
      record <- asRecord(value)
    } yield record

  def serializeChoiceResult(
      event: ExercisedEvent
  ): Either[String, String] =
    for {
      id <- schemaIdentifier(event.getInterfaceId.toScala.getOrElse(event.getTemplateId))
      converter <- jsonDict
        .getChoiceResult(id, schema.ChoiceName(event.getChoice))
        .toRight(s"Unknown choice ${event.getChoice} of $id")
      json <- toJson(converter, event.getExerciseResult)
    } yield json

  def deserializeChoiceResult(
      templateId: Identifier,
      interfaceId: Option[Identifier],
      choice: String,
      result: String,
  ): Either[String, JavaApi.Value] =
    for {
      id <- schemaIdentifier(interfaceId.getOrElse(templateId))
      converter <- protoDict
        .getChoiceResult(id, schema.ChoiceName(choice))
        .toRight(s"Unknown choice $choice of $id")
      value <- fromJson(converter, result)
    } yield value

  private def asRecord(value: JavaApi.Value): Either[String, JavaApi.DamlRecord] =
    value match {
      case r: JavaApi.DamlRecord => Right(r)
      case other => Left(s"Expected a record but got $other")
    }

  // Choices inherited from an interface are looked up on the interface, as the JSON API does.
  // The package name is used to find the latest version of the package, see the class comment.
  private def schemaIdentifier(id: Identifier): Either[String, schema.Identifier] =
    for {
      packageId <- Ref.PackageId.fromString(id.getPackageId)
      metadata <- packageMetadata.get(packageId).toRight(s"Unknown package ${id.getPackageId}")
    } yield schema.Identifier(
      schema.PackageId(packageId),
      schema.PackageName(metadata.name),
      schema.PackageVersion(metadata.version.toString),
      schema.ModuleName(id.getModuleName),
      schema.EntityName(id.getEntityName),
    )

  private def toJson(
      converter: Converter[scalaValue.Value, ujson.Value],
      value: JavaApi.Value,
  ): Either[String, String] =
    Try(ujson.write(converter.convert(scalaValue.Value.fromJavaProto(value.toProto)))).toEither.left
      .map(e => s"Failed to encode value: ${e.getMessage}")

  private def fromJson(
      converter: Converter[ujson.Value, scalaValue.Value],
      json: String,
  ): Either[String, JavaApi.Value] =
    Try {
      val value = converter.convert(ujson.read(json))
      JavaApi.Value.fromProto(scalaValue.Value.toJavaProto(value))
    }.toEither.left.map(e => s"Failed to decode value: ${e.getMessage}")
}
