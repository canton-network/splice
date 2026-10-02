# 10270 - RunbookValidatorPreflightIntegrationTest on ciperiodic: bob never sees alice's transfer offer because alice's transfer was submitted 1.5 s into her tap and failed with "At least one holding must be provided"; `tapAmulets` stopped waiting for the tap in #3920 (CircleCI build 510043)

New, family R (frontend `tapAmulets` returns before the tap is processed). Real test bug, timing dependent. Job
`preflight_validator_runbook_after_upgrade`, step "Run Validator preflight tests" (build 510043, step 149), cluster
ciperiodic, 5 of 6 tests passed. In "run through runbook against cluster validator", alice taps 100 USD and creates
a 10 Amulet transfer for bob through the wallet UI; bob's page then waits 20 s (`PREFLIGHT_DEFAULT_TIMEOUT_SECONDS`
default) for a `transfer-offer` row, and finds none. On the validator, alice's tap was received at 19:57:18.470 and
answered at 19:57:21.284. Her transfer request arrived at 19:57:19.997, while the tap was still in flight, and was
rejected at 19:57:20.481 with 400: `AssertionFailed ... 'At least one holding must be provided'`. So no offer ever existed.
The test got there because `tapAmulets`' "Making sure the tap has been processed" check passes immediately when the
page shows no error element: since #3920 (12f7b48ba7, 2026-02-11) the check is `find(errorElement).map { ... }`
inside `eventually`, and `None.map(...)` asserts nothing. Before #3920 the block ended in `shouldBe empty` followed by
the new-tap-row check, which waited for the tap.

- Build: CircleCI build 510043 step 149 (`preflight_validator_runbook_after_upgrade`), log
  `log/10270/build_510043_step_149_container_0.txt` (user-supplied); cluster logs fetched with
  `log/10270-10272-fetch.sh` into `log/10270/`.
- Splice version: not printed in the step log; code cited at origin/main 6bb1630d34 (last main commit before the
  run, 2026-10-01 19:05Z). The `tapAmulets` code is unchanged since 12f7b48ba7 (February), so the version does not matter here.
- Component: test helper `apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/WalletFrontendTestUtil.scala`.

## 1. Failing assertion

```
$ sed 's/\x1b\[[0-9;]*[mJK]//g' log/10270/build_510043_step_149_container_0.txt | grep -a -E 'Using cluster|Starting runbook|\*\*\* FAILED|was equal to|Tests: |Total time|Exceeded' | awk '!s[$0]++'
Using cluster hostname suffix: ciperiodic.network.canton.global
Starting runbook validator preflight tests: org.lfdecentralizedtrust.splice.integration.tests.runbook.RunbookValidatorPreflightIntegrationTest
[info] - run through runbook against cluster validator *** FAILED ***
[info]   None was equal to None (ValidatorPreflightIntegrationTest.scala:203)
[info] Tests: succeeded 5, failed 1, canceled 0, ignored 0, pending 0
[error] Total time: 151 s (0:02:31.0), completed Oct 1, 2026, 7:58:19 PM
Exceeded maximum retries (1 / 0), no more attempts Run Validator preflight tests
```

`ValidatorPreflightIntegrationTest.scala:203` is `eventually() { acceptButton should not be None }` in the clue
"Wait until transfer offer appears and can be accepted" on bob's page.

## 2. Validator: the transfer arrives during the tap and fails

```
$ zcat log/10270/validator-app.json.gz | jq -r '.[] | select(.timestamp >= "2026-10-01T19:57:07" and .timestamp <= "2026-10-01T19:57:22" and ((.jsonPayload.message//"")|test("HTTP POST /api/validator/v0/(register|wallet/tap|wallet/token-standard/transfers)"))) | "\(.timestamp) \(.jsonPayload.message|sub(" from \\([0-9.]+\\)";""))"'
2026-10-01T19:57:07.506Z HTTP POST /api/validator/v0/register: received request.
2026-10-01T19:57:10.369Z HTTP POST /api/validator/v0/register: Responding with status code: 200 OK
2026-10-01T19:57:14.086Z HTTP POST /api/validator/v0/register: received request.
2026-10-01T19:57:16.423Z HTTP POST /api/validator/v0/register: Responding with status code: 200 OK
2026-10-01T19:57:18.470Z HTTP POST /api/validator/v0/wallet/tap: received request.
2026-10-01T19:57:19.997Z HTTP POST /api/validator/v0/wallet/token-standard/transfers: received request.
2026-10-01T19:57:20.481Z HTTP POST /api/validator/v0/wallet/token-standard/transfers: Responding with status code: 400 Bad Request
2026-10-01T19:57:21.284Z HTTP POST /api/validator/v0/wallet/tap: Responding with status code: 200 OK
```

The `register` calls are alice's and bob's onboarding. The tap and the transfer are the same user (alice):

```
$ zcat log/10270/validator-app.json.gz | jq -r '.[] | select(.timestamp >= "2026-10-01T19:57:18" and .timestamp <= "2026-10-01T19:57:21" and ((.jsonPayload.message//"")|test("Decoded token .* for operation .(tap|createTokenStandardTransfer).$"))) | "\(.timestamp) \(.jsonPayload["trace-id"]) \(.jsonPayload.message)"'
2026-10-01T19:57:18.473Z 0a21b6119a9c2ff0c3dca8a87aee7bee Decoded token with subject = auth0|6abebb0ea80f6f02376d1960 for operation 'tap'
2026-10-01T19:57:20Z d4a2cb4cf259cec89fc51565d5328abf Decoded token with subject = auth0|6abebb0ea80f6f02376d1960 for operation 'createTokenStandardTransfer'
```

```
$ zcat log/10270/validator-app.json.gz | jq -r '.[] | select(.jsonPayload["trace-id"]=="d4a2cb4cf259cec89fc51565d5328abf" and ((.jsonPayload.message//"")|test("Decoded token|resulted in a gRPC"))) | "\(.timestamp) \(.jsonPayload.message | sub(".*Interpretation error: ";"... Interpretation error: ") | .[0:230])"'
2026-10-01T19:57:20Z Decoded token with subject = auth0|6abebb0ea80f6f02376d1960 for operation 'createTokenStandardTransfer'
2026-10-01T19:57:20.465Z ... Interpretation error: Error: User failure: UNHANDLED_EXCEPTION/DA.Exception.AssertionFailed:AssertionFailed (error category 9): The requirement 'At least one holding must be provided' was not met.
```

## 3. Why the test did not wait: `tapAmulets` after #3920

The pre-#3920 block asserted that no error element was present, then always looked for the new tap row. On main the
block is a `map` over `find(...)`, so with no error element the `eventually` returns at once:

```
$ git show 12f7b48ba7^:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/WalletFrontendTestUtil.scala | grep -n -A2 -E '^\s+\} shouldBe empty'; git show 6bb1630d34:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/WalletFrontendTestUtil.scala | sed -n '99,103p;128,137p'
60:        } shouldBe empty
61-        val txs = findAll(className("tx-row")).toSeq
62-        val txDatesAfter = txs.map(readDateFromRow)
    clue("Making sure the tap has been processed") {
      // This will have to change if we add a reload button here instead of auto-refreshing transactions.
      // The long eventually makes this robust against `StaleElementReferenceException` errors
      eventually(timeUntilSuccess = 2.minute) {
        find(className(errorDisplayElementClass)).map { errElem =>
                assertTapResultIsVisible()
              case Some(errDetails) =>
                fail(s"Tap failed: ${errElem.text.trim} ($errDetails)")
              case None =>
                assertTapResultIsVisible()
            },
          )
        }
      }
    }
```

Here alice's tap took 2.8 s (section 2), longer than the UI steps between the tap click and the transfer submit
(1.5 s), so the transfer ran against an empty wallet. When the tap is faster the bug is invisible, which is why
this helper has looked healthy since February.

## Verdict

- New family R: test helper bug, not infra and not the upgrade. Any frontend test that spends right after
  `tapAmulets` is exposed when a tap takes longer than the next UI steps. 10272 is a second hit, with a failing tap.
- Fix `s11/fix-10270-tap-amulets-wait-for-tap` (bc178c54a8): `find(errorElement) match { case None =>
  assertTapResultIsVisible(); case Some(errElem) => <the existing error cases> }`, which restores the pre-#3920 wait.
- Verified: `sbt apps-app/Test/scalafmtCheck` passes. Not verified: `apps-app/Test/compile` was aborted when the sandbox disk filled, and nothing was run against a cluster.
