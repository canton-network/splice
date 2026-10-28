// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import sbt.*

object Dependencies {
  private final val CD = CantonDependencies

  lazy val scala_version = "2.13.18"

  lazy val scalatest_version = "3.2.19"

  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version

  lazy val scalatestScalacheck = CD.scalatestScalacheck

  // Picked up automatically by the scalapb compiler. Contains common dependencies such as protocol buffers like google/protobuf/timestamp.proto
  lazy val scalapb_runtime =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  lazy val scalapb_runtime_grpc =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
  lazy val scalapb_json4 = "com.thesamet.scalapb" %% "scalapb-json4s" % "0.11.1"

  lazy val auth0 = "com.auth0" % "auth0" % "1.45.1"

  lazy val java_jwt = CantonDependencies.auth0_java

  lazy val jwks_rsa = CantonDependencies.auth0_jwks

  lazy val daml_lf_validation =
    "com.daml" %% "daml-lf-validation" % CantonDependencies.canton_library_version

  lazy val pekko_http_cors =
    "org.apache.pekko" %% "pekko-http-cors" % CantonDependencies.pekko_http_version

  lazy val pekko_connectors_google_cloud_storage =
    "org.apache.pekko" %% "pekko-connectors-google-cloud-storage" % "1.3.0"

  lazy val spray_json =
    "io.spray" %% "spray-json" % "1.3.6"

  lazy val pekko_spray_json =
    "org.apache.pekko" %% "pekko-http-spray-json" % CantonDependencies.pekko_http_version

  lazy val better_files = CantonDependencies.better_files

  lazy val google_cloud_storage =
    "com.google.cloud" % "google-cloud-storage" % "2.44.1"

  lazy val jaxb_abi = "javax.xml.bind" % "jaxb-api" % "2.3.1"

  lazy val commons_compress = "org.apache.commons" % "commons-compress" % "1.28.0"

  lazy val parallel_collections = "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0"

  lazy val zstd = CantonDependencies.zstd

  lazy val aws_s3 = "software.amazon.awssdk" % "s3" % CantonDependencies.aws_version

  lazy val s3mock_testcontainers = "com.adobe.testing" % "s3mock-testcontainers" % "4.12.4" % "test"

  private lazy val jackson_version = "2.22.4"
  private lazy val netty_version = "4.1.138.Final"
  private lazy val bouncy_castle_version = "1.86"

  // Applied to all projects as dependencyOverrides, so only these modules move and their parents stay as they are.
  // The older versions come from Canton's direct dependencies in CantonDependencies.scala (circe 0.14.2 -> jawn),
  // from libraries already on their latest release (scala-compiler 2.13.18 -> jline, httpclient5 5.6.4 -> httpcore5),
  // or from several libraries at once (jackson-databind: auth0, flyway, json4s and logstash).
  // Remove an entry once the build resolves that version without it.
  lazy val pinnedTransitiveVersions: Seq[ModuleID] = Seq(
    "buffer",
    "codec",
    "codec-http",
    "codec-http2",
    "common",
    "handler",
    "resolver",
    "transport",
    "transport-classes-epoll",
    "transport-native-unix-common",
  ).map(module => "io.netty" % s"netty-$module" % netty_version) ++ Seq(
    "bcprov",
    "bcpkix",
    "bcutil",
  ).map(module => "org.bouncycastle" % s"$module-jdk18on" % bouncy_castle_version) ++ Seq(
    "com.fasterxml.jackson.core" % "jackson-core" % jackson_version,
    "com.fasterxml.jackson.core" % "jackson-databind" % jackson_version,
    "com.fasterxml.jackson.core" % "jackson-annotations" % "2.22",
    "org.jline" % "jline" % "3.30.17",
    "org.postgresql" % "postgresql" % "42.7.14",
    "org.typelevel" %% "jawn-parser" % "1.7.0",
    "com.lihaoyi" %% "sourcecode" % "0.4.4",
    "org.apache.httpcomponents.core5" % "httpcore5" % "5.4.4",
    "org.apache.httpcomponents.core5" % "httpcore5-h2" % "5.4.4",
    "org.apache.httpcomponents.client5" % "httpclient5" % "5.6.4",
    "org.apache.logging.log4j" % "log4j-core" % "2.25.5",
    "org.apache.logging.log4j" % "log4j-api" % "2.25.5",
  )
}
