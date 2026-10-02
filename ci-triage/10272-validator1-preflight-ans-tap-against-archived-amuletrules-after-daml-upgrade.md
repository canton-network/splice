# 10272 - Validator1PreflightIntegrationTest on ciperiodic after the Daml upgrade: the ANS success page never loads because alice's tap failed 12 times against the just-archived AmuletRules (stale BFT scan consensus) and `tapAmulets` did not wait for it, so the ANS initial payment failed with ITR_InsufficientFunds (CircleCI build 510902)

Family R (second hit, see 10270) on top of an upgrade race. Job `preflight_after_daml_upgrade`, sbt testOnly of four
preflight suites (build 510902, step 147), cluster ciperiodic, 11 of 12 tests passed. In "test the Name Service UI of
a validator", alice taps and then reserves an ANS name. The Daml upgrade re-created AmuletRules on a new package at
record time 19:50:10.813 (`0020c0dce689` on `8fe7573f5535` archived, `000c9ff2dfc7` on `17b6f7c18efe` created). Alice's
tap arrived at 19:50:12.858. validator1's BftScanConnection kept getting the archived contract as the consensus
answer; only scan sv-1 returned the new one, as late as 19:50:23.119. Every tap attempt was rejected with
`LOCAL_VERDICT_INACTIVE_CONTRACTS`, and after 12 retries the tap returned 404 at 19:50:26.454. Because of the
`tapAmulets` bug (10270) the test had already created the ANS entry at 19:50:14.960 and accepted the subscription at
19:50:16.110. The initial payment failed with `ITR_InsufficientFunds(19.34...)` and gave up after 12 retries (400 at
19:50:26.498). The test then waited its full 200 s for a success page that could not appear.

- Build: CircleCI build 510902 step 147 (`preflight_after_daml_upgrade`), log
  `log/10272/build_510902_step_147_container_0.txt` (user-supplied); cluster logs from `log/10270-10272-fetch.sh`.
- Splice version: not printed; test code cited at origin/main 43064cab80 (2026-10-02 19:22Z).
- Component: test helper (`tapAmulets`, family R); validator `Tap` retry budget versus BFT scan lag after an
  AmuletRules upgrade (splice validator, product side).

## 1. Failing assertion

```
$ sed 's/\x1b\[[0-9;]*[mJK]//g' log/10272/build_510902_step_147_container_0.txt | grep -a -E 'Using cluster|still running after|\*\*\* FAILED|did not load|Tests: |Total time' | awk '!s[$0]++'
Using cluster hostname suffix: ciperiodic.network.canton.global
[info] *** Test still running after 1 minute, 28 seconds: suite name: Validator1PreflightIntegrationTest, test name: test the Name Service UI of a validator. 
[info] *** Test still running after 1 minute, 58 seconds: suite name: Validator1PreflightIntegrationTest, test name: test the Name Service UI of a validator. 
[info] *** Test still running after 2 minutes, 28 seconds: suite name: Validator1PreflightIntegrationTest, test name: test the Name Service UI of a validator. 
[info] *** Test still running after 2 minutes, 58 seconds: suite name: Validator1PreflightIntegrationTest, test name: test the Name Service UI of a validator. 
[info] *** Test still running after 3 minutes, 28 seconds: suite name: Validator1PreflightIntegrationTest, test name: test the Name Service UI of a validator. 
[info] - test the Name Service UI of a validator *** FAILED ***
[info]   The success page did not load. (AnsFrontendTestUtil.scala:67)
[info] Tests: succeeded 11, failed 1, canceled 0, ignored 0, pending 0
[error] Total time: 359 s (0:05:59.0), completed Oct 2, 2026, 7:53:52 PM
```

`AnsFrontendTestUtil.scala:67` is the `eventually(timeUntilSuccess = 200.seconds)` for the `ans-entries-button`. The
test body (`ValidatorPreflightIntegrationTest.scala`, "test the Name Service UI of a validator") calls
`tapAmulets(100)` directly before `reserveAnsNameFor(...)`.

## 2. validator1: the ANS steps run while the tap is still failing

```
$ zcat log/10272/validator1-validator-app.json.gz | jq -r '.[] | select(.timestamp >= "2026-10-02T19:50:10" and .timestamp <= "2026-10-02T19:50:27" and ((.jsonPayload.message//"")|test("HTTP POST /api/validator/v0/(register|wallet/tap|entry/create|wallet/subscription-requests)"))) | "\(.timestamp) \(.jsonPayload.message|sub(" from \\([0-9.]+\\)";"")|sub("subscription-requests/[0-9a-f]+\\.*";"subscription-requests/<cid>"))"'
2026-10-02T19:50:10.611Z HTTP POST /api/validator/v0/register: received request.
2026-10-02T19:50:12.858Z HTTP POST /api/validator/v0/wallet/tap: received request.
2026-10-02T19:50:12.893Z HTTP POST /api/validator/v0/register: Responding with status code: 200 OK
2026-10-02T19:50:14.960Z HTTP POST /api/validator/v0/entry/create: received request.
2026-10-02T19:50:15.468Z HTTP POST /api/validator/v0/entry/create: Responding with status code: 200 OK
2026-10-02T19:50:16.110Z HTTP POST /api/validator/v0/wallet/subscription-requests/<cid>: received request.
2026-10-02T19:50:26.454Z HTTP POST /api/validator/v0/wallet/tap: Responding with status code: 404 Not Found
2026-10-02T19:50:26.498Z HTTP POST /api/validator/v0/wallet/subscription-requests/<cid>: Responding with status code: 400 Bad Request
```

```
$ zcat log/10272/validator1-validator-app.json.gz | jq -r '.[] | select((.jsonPayload["trace-id"]=="9a86dfe4de8036a1e957b86ba22a4e6c" or .jsonPayload["trace-id"]=="16e1c6053c58dff94e2c7b3a8d908e82") and ((.jsonPayload.message//"")|test("failed with a retryable error|Giving up"))) | "\(.timestamp) \(.jsonPayload.message|gsub("\n";" ")|.[0:250])"' | awk '!s[substr($0,26,120)]++'
2026-10-02T19:50:13.455Z The operation 'Tap' failed with a retryable error (full stack trace omitted): category=Some(InvalidGivenCurrentSystemStateResourceMissing) ErrorInfoDetail(LOCAL_VERDICT_INACTIVE_CONTRACTS,HashMap(participant -> participant, CONTRACT_ID -> List(0020c0
2026-10-02T19:50:16.708Z The operation 'Accept subscription and make initial payment' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: the amulet operation failed with a Daml exception: COO_Error(ITR_InsufficientFunds(19.3423597679)). statusCode
2026-10-02T19:50:26.453Z The operation 'Tap' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. 
2026-10-02T19:50:26.486Z The operation 'Accept subscription and make initial payment' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. 
```

## 3. Why the tap failed: AmuletRules was replaced 2 s earlier and the scan consensus was stale

The upgrade transaction, as ingested by sv-1's DSO store (offset 2309):

```
$ zcat log/10272/sv-1-sv-app.json.gz | jq -r '.[] | select((.jsonPayload.message//"")|test("offset = 2309,")) | "\(.timestamp) \(.jsonPayload.message | gsub("\n";" ") | gsub("\\s+";" ") | [scan("synchronizerIdToRecordTime = [^ ]+ -> [^ ,)]+|contractId = [0-9a-f]{12}|templateId = [^ ,]+AmuletRules")] | join(" "))"'
2026-10-02T19:50:11.922Z synchronizerIdToRecordTime = global-domain::1220f2f73669... -> 2026-10-02T19:50:10.813410Z contractId = 000c9ff2dfc7 templateId = 17b6f7c18efe...:Splice.AmuletRules:AmuletRules contractId = 00f8f59f5ed4 contractId = 0020c0dce689 templateId = 17b6f7c18efe...:Splice.AmuletRules:AmuletRules
```

validator1's BFT scan reads at the time of the tap. The consensus answer is the archived contract on the old
package; scan sv-1 disagrees with the new one:

```
$ zcat log/10272/validator1-validator-app.json.gz | jq -r '.[] | select((.jsonPayload.message//"")|test("disagreed with consensus")) | "\(.timestamp) \(.jsonPayload.message | gsub("\n";" ") | gsub("\\s+";" ") | [scan("https://scan[^ ]+|consensus response: [A-Za-z]+|disagreeing response: [A-Za-z]+|contractId = [0-9a-f]{12}|templateId = [^ ,]+")] | join(" "))"'
2026-10-02T19:50:13.572Z https://scan.sv-1.ciperiodic.network.canton.global consensus response: Success contractId = 0020c0dce689 templateId = 8fe7573f5535...:Splice.AmuletRules:AmuletRules disagreeing response: SuccessfulResponse contractId = 000c9ff2dfc7 templateId = 17b6f7c18efe...:Splice.AmuletRules:AmuletRules
2026-10-02T19:50:14.289Z https://scan.sv-1.ciperiodic.network.canton.global consensus response: Success contractId = 0020c0dce689 templateId = 8fe7573f5535...:Splice.AmuletRules:AmuletRules disagreeing response: SuccessfulResponse contractId = 000c9ff2dfc7 templateId = 17b6f7c18efe...:Splice.AmuletRules:AmuletRules
2026-10-02T19:50:14.904Z https://scan.sv-1.ciperiodic.network.canton.global consensus response: Success contractId = 0020c0dce689 templateId = 8fe7573f5535...:Splice.AmuletRules:AmuletRules disagreeing response: SuccessfulResponse contractId = 000c9ff2dfc7 templateId = 17b6f7c18efe...:Splice.AmuletRules:AmuletRules
2026-10-02T19:50:17.933Z https://scan.sv-1.ciperiodic.network.canton.global consensus response: Success contractId = 0020c0dce689 templateId = 8fe7573f5535...:Splice.AmuletRules:AmuletRules disagreeing response: SuccessfulResponse contractId = 000c9ff2dfc7 templateId = 17b6f7c18efe...:Splice.AmuletRules:AmuletRules
2026-10-02T19:50:23.119Z https://scan.sv-1.ciperiodic.network.canton.global consensus response: Success contractId = 0020c0dce689 templateId = 8fe7573f5535...:Splice.AmuletRules:AmuletRules disagreeing response: SuccessfulResponse contractId = 000c9ff2dfc7 templateId = 17b6f7c18efe...:Splice.AmuletRules:AmuletRules
2026-10-02T19:54:50.508Z https://scan.sv.ciperiodic.network.canton.global consensus response: Success disagreeing response: HttpFailureResponse
2026-10-02T19:55:09.314Z https://scan.sv.ciperiodic.network.canton.global consensus response: Success disagreeing response: NonJsonHttpFailureResponse
2026-10-02T19:55:17.811Z https://scan.sv.ciperiodic.network.canton.global consensus response: Success disagreeing response: NonJsonHttpFailureResponse
```

The three disagreements at 19:54-19:55 are unrelated (`scan.sv` HTTP failures).

## Verdict

- Family R, second hit (parent 10270): with the fix the test would have waited for the tap. It would still have
  failed with `Tap failed: ...` after 2 min, since `LOCAL_VERDICT_INACTIVE_CONTRACTS` is not one of `tapAmulets`'
  retry cases, but it would fail at the tap with the real error instead of 200 s later on the ANS page.
- Upgrade race: the tap ran 2 s after AmuletRules was re-created, while the scans that formed the consensus still served the
  archived contract for at least 12.3 s (to 19:50:23.119). The validator's `Tap` retry budget (12 retries, 13.6 s here)
  is shorter than that lag. Product follow-ups for the validator owner, not written: retry the tap on
  `LOCAL_VERDICT_INACTIVE_CONTRACTS` for longer, or prefer the AmuletRules from the scan whose ledger is ahead. Test
  option: add `LOCAL_VERDICT_INACTIVE_CONTRACTS` to `tapAmulets`' re-tap cases (not done; the UI error text for this
  404 was not captured).
- Not verified: the other scans' ingestion lag directly (scan logs were not fetched); the 12.3 s is a lower bound
  from validator1's disagreement log.
