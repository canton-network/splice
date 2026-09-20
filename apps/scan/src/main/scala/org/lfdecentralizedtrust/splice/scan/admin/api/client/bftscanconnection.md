> Do we gain anything from returning a Future.failed(BaseAppConnection.UnexpectedHttpJsonResponse) with the preserved HTTP error code instead of a generic Future.failed(ConsensusNotReached)?

A quorum of matching error responses is a valid consensus, and the original exception (with its status code) is what gets propagated, not `ConsensusNotReached`.

**How it works today** (`BftScanConnection.executeCall`, ~L1198–1245):

- Every response, success *or* failure, is bucketed via `keyToGroupResponses`. HTTP failures (`UnexpectedHttpJsonResponse`, `UnexpectedHttpTextResponse`, `UnexpectedHttpNonJsonResponse`, `HttpCommandException`) are keyed by **status code + body**, so `f+1` scans returning the same `404 {"error":"..."}` count as agreement.
- When a bucket hits `nTargetSuccess`, `finalResponse.tryComplete(response.map(...))` is called with the *original* `Try` — so for an error bucket the future fails with the original `UnexpectedHttpJsonResponse`/`HttpCommandException`, status code intact.
- Only "random" exceptions (`ExceptionFailureResponse`: network errors, deserialization bugs) never compare equal, so they can never form a consensus and only ever contribute to `ConsensusNotReached`.
- `ConsensusNotReached` is reserved for the case where no bucket reached quorum, and `bftCall` then maps it to a `502 BadGateway`.

**Where it matters (why this is worth keeping):**

1. **Retry classification** — `RetryProvider.retryForClientCalls` (RetryProvider.scala ~L692–736) treats `UnexpectedHttpJsonResponse` as transient, and `HttpCommandException`/`HttpErrorWithHttpCode` as transient or fatal depending on `retryableHttpStatusCodes`. A consensus `400`/`404` becomes a fast fatal failure instead of retrying against a 502 until the budget is exhausted. `ConsensusNotReached` would lose that.
2. **Proxying to end users** — `HttpErrorHandler.exceptionsDirective` (L156–168) turns `HttpCommandException`/`HttpErrorWithHttpCode` into an HTTP response with the *same* status code and message. The validator's `HttpScanProxyHandler` / `HttpTokenStandardScanProxyHandler` and Scan's own BFT-backed endpoints therefore surface e.g. `400 "migration id X not found"` or `429` to the client, rather than a misleading `502 Failed to reach consensus`.
3. **Debuggability** — the agreed error body ("message") is far more informative than a generic disagreement message.

**Concrete endpoints where an error consensus is meaningful:** anything where the server returns a semantically meaningful non-2xx that is *not* already mapped to `None` by the client command — e.g. `getUpdateHistory`/`getUpdatesBefore` with a bad migration id or `after` cursor (`400`), `getAcsSnapshotAt`/`getHoldingsStateAt` with invalid `record_time` (`400`), `getRewardAccountingBatch` with an unknown hash, `getBulkObjectChecksums`, or rate-limiting (`429`). Note that most `404`s (see `nonErrorStatusCodes` and the `...Response.NotFound` cases in `HttpScanAppClient`) are already converted to `Success(None)` *before* the BFT layer, so those reach consensus as ordinary successes.

**Bottom line:** swapping the preserved error for a blanket `ConsensusNotReached` would lose retry semantics and status-code fidelity for proxied callers with no gain; the only things that should end in `ConsensusNotReached` are genuine disagreement or non-comparable failures (network/deserialization), which is exactly the current behaviour.

> Make a ASCII table with the different failure modes , and the exact scala return type of the SingleScanConnection call (should be the result of runHttpCmd)

Here is the pipeline as it actually executes. The order matters, because the first stage to fail determines the exception type, and HttpCommandException sits at the earliest stage:
```
SingleScanConnection.getX(...)
  └─ BaseAppConnection.runHttpCmd(url, command)             AppConnection.scala:64
       ├─ [1] HttpClient.createHttpFn (wraps pekko request)  HttpClient.scala:86–137
       │       transport error               → Future.failed(raw exception)
       │       4xx/5xx ∉ nonErrorStatusCodes → Future.failed(HttpCommandException)
       │       unknown content-type          → Future.failed(HttpCommandException)
       ├─ [2] guardrail-generated client (command.submitRequest)
       │       status not in scan.yaml       → Left(Right(HttpResponse))
       │       body deserialization fails    → Left(Left(Throwable))
       ├─ [3] runHttpCmd.handleFailure                       AppConnection.scala:77–137
       │       Left(Right(resp))             → Unexpected*Response by content-type
       │       Left(Left(t))                 → t re-thrown unchanged
       └─ [4] command.handleResponse → handleOk → toFuture   AppConnection.scala:57,145
               Left(msg)                     → StatusRuntimeException(UNAVAILABLE)
```

Failure modes and the exact result of runHttpCmd (Future[Result]):
```
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| #   | Failure mode                                                   | Stage     | Result of runHttpCmd                                                   |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 1   | Network/transport failure (DNS, connection refused, TLS,       | [1]       | Future.failed(<raw exception>) e.g. java.net.ConnectException,         |
|     | request timeout, stream reset)                                 |           | pekko TimeoutException, StreamTcpException, ...                        |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 2   | HTTP 4xx/5xx and code ∉ HttpCommand.nonErrorStatusCodes        | [1]       | Future.failed(HttpCommandException(request, status, body))             |
|     | (regardless of whether scan.yaml documents the code!)          |           |   body = ErrorResponseBody if `{"error": ...}` parses,                 |
|     |                                                                |           |   else RawResponse(bodyString)                                         |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 3   | Content-Type not in {application/json, octet-stream,           | [1]       | Future.failed(HttpCommandException(request, status, RawResponse(body)))|
|     | text/plain} and not NoContentType (e.g. text/html from an      |           | (even for 200 OK)                                                      |
|     | ingress/load balancer error page)                              |           |                                                                        |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 4   | Status code passed [1] but is not defined in scan.yaml for     | [2]+[3]   | Future.failed(BaseAppConnection.UnexpectedHttpJsonResponse(status,json))|
|     | this operation, body is application/json and parses            |           |                                                                        |
|     | Reachable for: 2xx/3xx codes not in yaml, or 4xx/5xx codes     |           |                                                                        |
|     | listed in nonErrorStatusCodes but missing from yaml            |           |                                                                        |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 5   | Same as 4, but application/json body fails to parse            | [2]+[3]   | Future.failed(BaseAppConnection.UnexpectedHttpMalformedJsonResponse)   |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 6   | Same as 4, but Content-Type is text/plain or text/html         | [2]+[3]   | Future.failed(BaseAppConnection.UnexpectedHttpTextResponse(status,txt))|
|     | (text/html only reachable here if it slipped past [1], i.e.    |           |                                                                        |
|     | practically text/plain)                                        |           |                                                                        |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 7   | Same as 4, any other Content-Type / no content type            | [2]+[3]   | Future.failed(BaseAppConnection.UnexpectedHttpNonJsonResponse(status)) |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 8   | Status code IS defined in scan.yaml but body does not          | [2]+[3]   | Future.failed(<circe/unmarshal exception>) e.g. io.circe.DecodingFailure,|
|     | deserialize into the generated response type                   |           | ParsingFailure, UnsupportedContentTypeException                        |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 9   | Response deserialized OK, but handleOk returns Left(msg)       | [4]       | Future.failed(io.grpc.StatusRuntimeException                           |
|     | (e.g. invalid contract-id/party-id, TemplateJsonDecoder error) |           |   Status.UNAVAILABLE.withDescription(msg))                             |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 10  | Response deserialized OK, but handleOk's PartialFunction has   | [4]       | Future.failed(scala.MatchError)                                        |
|     | no case for that response variant                              |           |                                                                        |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
| 11  | HTTP code in nonErrorStatusCodes AND in scan.yaml AND handled  | –         | Future.successful(Result), typically None (e.g. 404 → None)            |
|     | by handleOk (e.g. 404 → NotFound case)                         |           |                                                                        |
+-----+----------------------------------------------------------------+-----------+------------------------------------------------------------------------+
```

How this maps onto keyToGroupResponses in BftScanConnection (L1252–1277)
```
Row 2,3        HttpCommandException            → HttpFailureResponse(status, {"message": ...})   groupable → can reach consensus
Row 4          UnexpectedHttpJsonResponse      → HttpFailureResponse(status, json)               groupable
Row 6          UnexpectedHttpTextResponse      → TextFailureResponse(status, text)               groupable
Row 7          UnexpectedHttpNonJsonResponse   → NonJsonHttpFailureResponse(status)              groupable
Row 5          UnexpectedHttpMalformedJsonResp → ExceptionFailureResponse(t)   (not special-cased!) never groupable
Row 1,8,9,10   everything else                 → ExceptionFailureResponse(t)                     never groupable
```
