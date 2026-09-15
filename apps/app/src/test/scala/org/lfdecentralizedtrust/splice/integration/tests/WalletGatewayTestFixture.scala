// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.topology.PartyId
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.lfdecentralizedtrust.splice.util.ProcessTestUtil
import org.scalatest.Suite

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.Using
import scala.util.control.NonFatal

/** Starts `@canton-network/wallet-gateway-remote` as a child process for CIP-103
  * frontend e2e, using Splice IT self-signed Ledger API auth
  * (`unsafe-jwt-hmac-256`, secret `test`, audience `https://canton.network.global`)
  * against alice's HTTP JSON API (`http://127.0.0.1:6501`).
  *
  * Party allocation is gateway-first: `createWallet` with
  * `signingProviderId=participant` always `POST /v2/parties` on the participant.
  * Call [[createParticipantWallet]] before creating `VoteDelegation` for that party.
  */
trait WalletGatewayTestFixture extends ProcessTestUtil { this: Suite & BaseTest =>

  protected val walletGatewayPort: Int = 3030
  protected val walletGatewayNetworkId: String = "canton:splice-it"
  protected val walletGatewayNetworkName: String = "Splice IT"
  protected val walletGatewayUserApi: String = s"http://localhost:$walletGatewayPort/api/v0/user"
  protected val walletGatewayDappApi: String = s"http://localhost:$walletGatewayPort/api/v0/dapp"
  // Workspace bin from `npm ci` in apps/ (see apps/package.json).
  protected val walletGatewayBin: Path = Path.of("apps/node_modules/.bin/wallet-gateway")
  // Stable, gitignored paths so CI exports the log and local runs are inspectable.
  protected val walletGatewayDir: Path = Path.of("apps/app/src/test/resources/wallet-gateway")
  protected val walletGatewayLog: Path = Path.of("log/wallet-gateway.log")

  // Match include/validators/alice-validator.conf + simple-topology-canton.conf.
  protected val walletGatewayLedgerApiBaseUrl: String = "http://127.0.0.1:6501"
  protected val walletGatewayAuthClientId: String = "alice_wallet_user"
  protected val walletGatewayAdminClientId: String = "alice_validator_user"
  protected val walletGatewayClientSecret: String = "test"
  protected val walletGatewayAudience: String = "https://canton.network.global"

  private val gatewayProcess = new AtomicReference[Option[ProcessTestUtil.Process]](None)
  private val httpClient: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  /** Fresh sqlite files under `dir` so the gateway treats the DB as new and
    * runs config bootstrap (idp + network). `"type": "memory"` skips bootstrap
    * in wallet-gateway-remote because `exists` defaults to true.
    *
    * @param synchronizerId must be the global / decentralized synchronizer.
    *   Without it, wallet-gateway-remote picks `connectedSynchronizers[0]`,
    *   which for alice is `splitwell`, and SV create of VoteDelegation then
    *   fails with UNKNOWN_INFORMEES.
    */
  protected def walletGatewayConfigJson(dir: Path, synchronizerId: String): String = {
    val storeDb = dir.resolve("store.sqlite").toAbsolutePath.toString
    val signingDb = dir.resolve("signing.sqlite").toAbsolutePath.toString
    s"""
       |{
       |  "kernel": { "id": "remote-da", "clientType": "remote" },
       |  "logging": { "level": "info", "format": "pretty" },
       |  "server": {
       |    "port": $walletGatewayPort,
       |    "dappPath": "/api/v0/dapp",
       |    "userPath": "/api/v0/user",
       |    "allowedOrigins": ["http://localhost:3213"],
       |    "requestSizeLimit": "5mb",
       |    "requestRateLimit": 10000,
       |    "trustProxy": false,
       |    "admin": "sub"
       |  },
       |  "store": { "connection": { "type": "sqlite", "database": "$storeDb" } },
       |  "signingStore": { "connection": { "type": "sqlite", "database": "$signingDb" } },
       |  "bootstrap": {
       |    "idps": [
       |      { "id": "idp-splice-it", "type": "self_signed", "issuer": "unsafe-auth" }
       |    ],
       |    "networks": [
       |      {
       |        "id": "$walletGatewayNetworkId",
       |        "name": "$walletGatewayNetworkName",
       |        "description": "Splice IntegrationTest alice participant (self-signed HS256)",
       |        "identityProviderId": "idp-splice-it",
       |        "synchronizerId": "$synchronizerId",
       |        "auth": {
       |          "method": "self_signed",
       |          "issuer": "self-signed",
       |          "audience": "$walletGatewayAudience",
       |          "scope": "openid email daml_ledger_api offline_access",
       |          "clientId": "$walletGatewayAuthClientId",
       |          "clientSecret": "$walletGatewayClientSecret"
       |        },
       |        "adminAuth": {
       |          "method": "self_signed",
       |          "issuer": "self-signed",
       |          "scope": "daml_ledger_api",
       |          "audience": "$walletGatewayAudience",
       |          "clientId": "$walletGatewayAdminClientId",
       |          "clientSecret": "$walletGatewayClientSecret"
       |        },
       |        "ledgerApi": { "baseUrl": "$walletGatewayLedgerApiBaseUrl" }
       |      }
       |    ]
       |  }
       |}
       |""".stripMargin
  }

  protected def startWalletGateway(synchronizerId: String): Unit = {
    gatewayProcess.get().foreach { _ =>
      fail(
        "wallet-gateway is already running; stop the existing process before starting another"
      )
    }
    resetWalletGatewayDir()
    Files.createDirectories(walletGatewayLog.getParent)
    val configPath = walletGatewayDir.resolve("config.json")
    Files.writeString(
      configPath,
      walletGatewayConfigJson(walletGatewayDir, synchronizerId),
      StandardCharsets.UTF_8,
    )

    if (!Files.isExecutable(walletGatewayBin)) {
      fail(s"$walletGatewayBin is missing; run npm install in apps/")
    }
    val builder = new ProcessBuilder(
      walletGatewayBin.toString,
      "-c",
      configPath.toString,
    )
    builder.redirectErrorStream(true)
    builder.redirectOutput(walletGatewayLog.toFile)
    val javaProc = builder.start()
    gatewayProcess.set(Some(ProcessTestUtil.Process(javaProc)))

    try {
      eventually(timeUntilSuccess = 4.minutes) {
        if (!javaProc.isAlive) {
          fail(
            s"wallet-gateway process exited early (code=${javaProc.exitValue()}). See $walletGatewayLog"
          )
        }
        val status =
          try {
            httpClient
              .send(
                HttpRequest
                  .newBuilder(URI.create(s"http://localhost:$walletGatewayPort/readyz"))
                  .GET()
                  .build(),
                HttpResponse.BodyHandlers.ofString(),
              )
              .statusCode()
          } catch {
            // ConnectException is not retryable in BaseTest.eventually by default;
            // convert to an assertion failure so we keep polling until listen.
            case e: IOException =>
              fail(
                s"wallet-gateway not ready yet on :$walletGatewayPort (${e.getClass.getSimpleName})"
              )
          }
        status shouldBe 200
      }
    } catch {
      case NonFatal(e) =>
        stopWalletGateway()
        fail(
          s"wallet-gateway failed to become ready on :$walletGatewayPort. See $walletGatewayLog",
          e,
        )
    }
  }

  protected def stopWalletGateway(): Unit = {
    gatewayProcess.getAndSet(None).foreach { proc =>
      proc.destroyAndWait()
    }
  }

  private def resetWalletGatewayDir(): Unit = {
    Files.createDirectories(walletGatewayDir)
    Using.resource(Files.list(walletGatewayDir)) { stream =>
      stream.forEach { path =>
        if (path.getFileName.toString != ".gitignore") {
          Files.deleteIfExists(path)
        }
      }
    }
  }

  /** Mint a self-signed user token, open a network session, and allocate a
    * participant-signed wallet. Returns the new ledger party id.
    *
    * The ledger user named by [[walletGatewayAuthClientId]] must already exist
    * on the participant; otherwise Canton returns 403 UserNotFound and
    * `addSession` fails with "Failed to add session".
    */
  protected def createParticipantWallet(partyHint: String): PartyId = {
    val accessToken = selfSignedAccessToken(walletGatewayAuthClientId)
    addSession(accessToken)
    val partyIdStr = createWallet(accessToken, partyHint)
    PartyId.tryFromProtoPrimitive(partyIdStr)
  }

  private def selfSignedAccessToken(clientId: String): String = {
    val result = userRpc(
      method = "selfSignedAccessToken",
      params = Json.obj(
        "networkId" -> walletGatewayNetworkId.asJson,
        "clientId" -> clientId.asJson,
      ),
      bearer = None,
    )
    result.hcursor
      .downField("accessToken")
      .as[String]
      .getOrElse(fail(s"selfSignedAccessToken missing accessToken: $result"))
  }

  private def addSession(accessToken: String): Unit = {
    userRpc(
      method = "addSession",
      params = Json.obj(
        "origin" -> "http://localhost:3213".asJson,
        "networkId" -> walletGatewayNetworkId.asJson,
      ),
      bearer = Some(accessToken),
    )
    ()
  }

  private def createWallet(accessToken: String, partyHint: String): String = {
    val result = userRpc(
      method = "createWallet",
      params = Json.obj(
        "partyHint" -> partyHint.asJson,
        "signingProviderId" -> "participant".asJson,
        "primary" -> true.asJson,
      ),
      bearer = Some(accessToken),
    )
    result.hcursor
      .downField("wallet")
      .downField("partyId")
      .as[String]
      .getOrElse(fail(s"createWallet missing wallet.partyId: $result"))
  }

  private def userRpc(method: String, params: Json, bearer: Option[String]): Json = {
    val body = Json
      .obj(
        "jsonrpc" -> "2.0".asJson,
        "id" -> 1.asJson,
        "method" -> method.asJson,
        "params" -> params,
      )
      .noSpaces
    val builder = HttpRequest
      .newBuilder(URI.create(walletGatewayUserApi))
      .timeout(Duration.ofSeconds(60))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    bearer.foreach(token => builder.header("Authorization", s"Bearer $token"))
    val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    withClue(s"User API $method HTTP ${response.statusCode()}: ${response.body()}") {
      response.statusCode() shouldBe 200
    }
    val json = parse(response.body()).getOrElse(fail(s"Invalid JSON: ${response.body()}"))
    json.hcursor.get[Json]("error") match {
      case Right(err) if !err.isNull => fail(s"User API $method error: $err")
      case _ => ()
    }
    json.hcursor
      .get[Json]("result")
      .getOrElse(fail(s"User API $method missing result: ${response.body()}"))
  }
}
