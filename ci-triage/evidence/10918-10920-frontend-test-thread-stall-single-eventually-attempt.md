# 10918, 10920 - compose / LocalNet wallet: test thread stalls 2-3.5 min inside the only `eventually` attempt, deadline passes, stale first read fails (run 40969391292)

Root-caused to the test framework level, cause of the stall itself open. Both refs are from post-merge main run
40969391292 (6d404e421b, Canton 3.7.0), two different jobs on two different self-hosted docker runners. In both,
the test thread issued one WebDriver read of the transaction list right after an action (login, tap), Firefox and
geckodriver answered within milliseconds, and then the test thread did nothing for 127.9 s (10918) and 208.4 s
(10920). The rest of the test JVM, the browser, the wallet polling and the BiDi console relay kept running normally
the whole time. The stall used up the `eventually` budget (20 s in 10918, 2 min in 10920) inside the first attempt,
so `BaseTest.eventually` rethrew that attempt's stale observation (no rows yet / no new tap yet) without a second
attempt. The page dumped right after the failure shows the expected Tap row in both jobs. Not a wallet, backend or
compose bug. Where the test thread blocked is not determined: no thread dump, no Selenium read timeout (10920 exceeds
the 180 s default), no DNS errors; both stalls end within 2.6 s of each other (23:26:16.5 and 23:26:19.1) on
different runner pods. No recurrence of this shape in the 12 days since.

- Run: https://github.com/canton-network/splice/actions/runs/40969391292, main 6d404e421b ("[ci] Bump
  eslint-plugin-react-hooks from 5.2.0 to 7.1.1 in /apps (#8027)").
  - 10918: job 123782957691 `ci / scala_test_docker_compose / docker-compose (1)`, attempt 1, runner
    self-hosted-docker-large-runner-ppddx, DockerComposeValidatorFrontendIntegrationTest.
  - 10920 (closed 2026-10-30 as dup of 10918): job 123783162222 `ci / scala_test_with_docker_no_canton /
    docker-no-canton (0)`, attempt 1, runner self-hosted-docker-large-runner-jsb54, LocalNetFrontendIntegrationTest.
- Runtime canton: 3.7.0. The test-side `BaseTest.eventually` is compiled from the vendored `canton/` at the run sha
  (it runs in the test JVM, not in the Canton binary), so it is cited from `git show 6d404e421b:canton/...`.
- Component: test framework (frontend `eventually` with a single stalled attempt); stall origin unknown.

Artifacts: `log/10918/logs-docker-compose-1/`, `log/10920/logs-docker-no-canton-0/` (repo root), job logs
`log/10918/job.log`, `log/10920/job.log`.

## 1. Failing assertions

10918: `taps should have length 1` in the clue "We see Alice's tap", after restoring the compose validator from backup.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10918/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - docker-compose based validator works *** FAILED ***
[info] Run completed in 11 minutes, 20 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
```
```
sed -E 's/\x1b\[[0-9;]*m//g' log/10918/job.log | grep -a -A12 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -8
```
```
[info] - docker-compose based validator works *** FAILED ***
[info]   List() had length 0 instead of expected length 1 (DockerComposeValidatorFrontendIntegrationTest.scala:263)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
[info]   at org.scalatest.matchers.should.Matchers$ResultOfHaveWordForExtent.length(Matchers.scala:2780)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.DockerComposeValidatorFrontendIntegrationTest.$anonfun$new$34(DockerComposeValidatorFrontendIntegrationTest.scala:263)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon.$anonfun$eventually$1(FrontendIntegrationTest.scala:400)
[info]   at scala.util.Try$.apply(Try.scala:217)
```

10920: `forAtLeast(1)` over the new taps in `tapAmulets` (WalletFrontendTestUtil.scala:67).

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10920/job.log | grep -a -A6 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -6
```
```
[info] - docker-compose based localnet works for single synchronizer *** FAILED ***
[info]   forAtLeast(1) failed, because no element satisfied the assertion block: 
[info]    
[info]   in List() (WalletFrontendTestUtil.scala:67)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.enablers.UnitInspectorAsserting$$anon$1.indicateFailure(InspectorAsserting.scala:326)
```

## 2. Test code and the retry budget (run sha)

The check in 10918 is a default frontend `eventually()`:

```
git show 6d404e421b:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/DockerComposeValidatorFrontendIntegrationTest.scala | sed -n 259,268p
```
```
        clue(s"We see Alice's tap") {
          eventually() {
            val txs = findAll(className("tx-row")).toSeq
            val taps = txs.flatMap(readTapFromRow)
            taps should have length 1
            taps.head.tapAmountAmulet shouldBe walletUsdToAmulet(
              BigDecimal.valueOf(aliceTap),
              1.0 / taps.head.usdToAmulet,
            )
          }
```

`FrontendTestCommon.eventually` defaults to 20 s with a 100 ms poll:

```
git show 6d404e421b:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala | sed -n 392,398p
```
```
  override def eventually[T](
      timeUntilSuccess: FiniteDuration = 20.seconds,
      maxPollInterval: FiniteDuration = 100.millis,
      retryOnTestFailuresOnly: Boolean = true,
      logElapsed: Option[String] = None,
  )(testCode: => T): T =
    super.eventually(timeUntilSuccess, maxPollInterval, retryOnTestFailuresOnly, logElapsed) {
```

The underlying `BaseTest.eventually` checks the deadline only after an attempt fails, and rethrows that attempt's
failure when the deadline has passed. One attempt that outlasts the whole budget is therefore final:

```
git show 6d404e421b:canton/community/testing/src/main/scala/com/digitalasset/canton/BaseTest.scala | sed -n 613,635p
```
```
    @tailrec def go(sleepMs: Long): T = {
      val result = Try(testCode)
      result match {
        case Success(value) =>
          logElapsed.foreach { case (theLogger, msg) =>
            theLogger.debug(s"$msg succeeded after $timeElapsed")
          }
          value
        case Failure(ex) =>
          val retry = !retryOnTestFailuresOnly || ex.isInstanceOf[TestFailedException]
          if (retry && hasTimeLeft) {
            val nextSleepMs = sleep(sleepMs)
            go(nextSleepMs)
          } else {
            logElapsed.foreach { case (theLogger, msg) =>
              theLogger.debug(s"$msg failed after $timeElapsed")
            }
            throw ex
          }
      }
    }

    go(sleepMs = 1L)
```

10920's check is `eventually(timeUntilSuccess = 2.minute)`:

```
git show 6d404e421b:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/WalletFrontendTestUtil.scala | sed -n 99,103p
```
```
    clue("Making sure the tap has been processed") {
      // This will have to change if we add a reload button here instead of auto-refreshing transactions.
      // The long eventually makes this robust against `StaleElementReferenceException` errors
      eventually(timeUntilSuccess = 2.minute) {
        find(className(errorDisplayElementClass)).fold(assertTapResultIsVisible()) { errElem =>
```

## 3. 10918: the clue ran 128 s, the browser saw exactly one `.tx-row` lookup

```
zcat log/10918/logs-docker-compose-1/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished '|after restoring|We see Alice|Login as alice|Alice is already|Alice can login|withComposeValidator|compose|eventually" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | grep -a 'T23:2' | cut -c1-200 | tail -10
```
```
2026-10-29T23:24:10.963Z Running clue: Alice can login and is already onboarded",
2026-10-29T23:24:10.963Z Running clue: (act) Login as alice",
2026-10-29T23:24:10.998Z Retrying StaleElementReferenceException\n  at org.openqa.selenium.remote.ErrorCodec.decode(ErrorCodec.java:169)\n  at org.openqa.selenium.remote.codec.w3c.W3CHttpResponseCodec
2026-10-29T23:24:11.197Z Finished clue: (act) Login as alice",
2026-10-29T23:24:11.198Z Running clue: (check) Alice is already onboarded",
2026-10-29T23:24:11.206Z Finished clue: (check) Alice is already onboarded",
2026-10-29T23:24:11.206Z Finished clue: Alice can login and is already onboarded",
2026-10-29T23:24:11.208Z Running clue: We see Alice's tap",
2026-10-29T23:26:19.148Z Failed clue: We see Alice's tap",
2026-10-29T23:26:32.017Z Test failed: 'DockerComposeValidatorFrontendIntegrationTest/docker-compose based validator works', message: List() had length 0 instead of expected length 1, location: SeeStac
```

All `.tx-row` lookups after the login (geckodriver Marionette log, epoch ms; 1793316251100 = 23:24:11.100):

```
cd log/10918/logs-docker-compose-1
B=browser.org.lfdecentralizedtrust.splice.integration.tests.DockerComposeValidatorFrontendIntegrationTest.frontend.0.1.log.gz; F=1793316251100; T=1793316395000; zcat $B | awk -F'\t' -v f=$F -v t=$T '$1>=f && $1<=t && $2=="Marionette"' | grep -a -A1 'tx\\\\-row' | grep -a -v '^--' | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{20,}/<uuid>/g' | awk -F'\t' '{print strftime("%H:%M:%S",substr($1,1,10),1)"."substr($1,11,3), substr($4,1,140)}'
```
```
23:24:11.208 0 -> [0,2110,"WebDriver:FindElements",{"using":"css selector","value":".tx\\-row"}]
23:24:11.209 0 <- [1,2110,null,[]]
```

Gaps over 5 s between a geckodriver response and the next command over the whole browser session. The first two
are the backup and the restore steps; the third is inside the 20 s `eventually`:

```
B=browser.org.lfdecentralizedtrust.splice.integration.tests.DockerComposeValidatorFrontendIntegrationTest.frontend.0.1.log.gz; zcat $B | awk -F'\t' '$2=="webdriver::server" {d=$4; if (d ~ /^<- /) {lastresp=$1; lr=substr(d,1,60)} else if (d ~ /^-> /) { if (lastresp && $1-lastresp > 5000) printf "%s gap %.1fs after [%s] next [%s]\n", strftime("%H:%M:%S",substr(lastresp,1,10),1), ($1-lastresp)/1000, lr, substr(d,1,70); lastresp=0 } }' | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{20,}/<uuid>/g'
```
```
23:17:34 gap 157.3s after [<- 200 OK {"value":null}] next [-> POST /session/<uuid>/url {]
23:21:26 gap 164.3s after [<- 200 OK {"value":"2027-01-27 23:21"}] next [-> POST /session/<uuid>/url {]
23:24:11 gap 127.9s after [<- 200 OK {"value":[]}] next [-> POST /session/<uuid>/element {]
```

The command after the gap is the failure dump (screenshot of `body`), not a second `.tx-row` lookup:

```
B=browser.org.lfdecentralizedtrust.splice.integration.tests.DockerComposeValidatorFrontendIntegrationTest.frontend.0.1.log.gz; F=1793316251209; T=1793316400000; zcat $B | awk -F'\t' -v f=$F -v t=$T '$1>=f && $1<=t && $2 !~ /RemoteAgent/' | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{20,}/<uuid>/g' | awk -F'\t' '{print strftime("%H:%M:%S",substr($1,1,10),1)"."substr($1,11,3), $2, substr($4,1,170)}' | head -6
```
```
23:24:11.209 Marionette 0 <- [1,2110,null,[]]
23:24:11.209 webdriver::server <- 200 OK {"value":[]}
23:26:19.150 webdriver::server -> POST /session/<uuid>/element {
23:26:19.151 Marionette 0 -> [0,2111,"WebDriver:FindElement",{"using":"tag name","value":"body"}]
23:26:19.152 Marionette 0 <- [1,2111,null,{"value":{"element-6066-11e4-a52e-4f735466cecf":"<uuid>"}}]
23:26:19.153 webdriver::server <- 200 OK {"value":{"element-6066-11e4-a52e-4f735466cecf":"<uuid>"}}
```

So the only attempt ran at 23:24:11.208, 2 ms after the login check, and the test thread made no further WebDriver
call until the failure at 23:26:19.148, 108 s past the 20 s deadline.

## 4. 10918: the rows were loading normally; the page at failure has the Tap row

The wallet polled `/wallet/transactions` about once a second through the stall; the first response with `items`
came 0.3 s after the only lookup (the logged bodies are truncated by geckodriver, so their content is not read here):

```
B=browser.org.lfdecentralizedtrust.splice.integration.tests.DockerComposeValidatorFrontendIntegrationTest.frontend.0.1.log.gz; F=1793316250000; T=1793316380000; zcat $B | awk -F'\t' -v f=$F -v t=$T '$1>=f && $1<=t && $4 ~ /wallet got response/ && $4 ~ /items/' | awk -F'\t' '{print strftime("%H:%M:%S",substr($1,1,10),1)"."substr($1,11,3)}' | sed -n '1p;$p'; zcat $B | awk -F'\t' -v f=$F -v t=$T '$1>=f && $1<=t && $4 ~ /wallet got response/ && $4 ~ /items/' | wc -l
```
```
23:24:11.525
23:26:18.872
127
```

Transaction rows in the page source saved at 23:26:19.241:

```
zcat webpage-26-10-29-23.26.19.241.html.gz | sed 's/></>\n</g' | grep -o -E 'class="[^"]*(tx-row |tx-action|tx-subtype|tx-amount-amulet|tx-row-cell-date)[^"]*"[^<]*(<p[^>]*>)?[^<]*' | sed -E 's/class="[^"]*(tx-row |tx-action|tx-subtype|tx-amount-amulet|tx-row-cell-date)[^"]*"/\1/' | cut -c1-120
```
```
tx-row >
tx-action>Sent
tx-subtype>(ANS Entry Initial Payment Collected)
tx-row-cell-date>
tx-amount-amulet>+0 AMT
tx-row >
tx-action>Sent
tx-subtype>(Subscription Initial Payment Accepted)
tx-row-cell-date>
tx-amount-amulet>-200 AMT
tx-row >
tx-action>Balance Change
tx-subtype>(Tap)
tx-row-cell-date>
tx-amount-amulet>+24,680 AMT
```

Exactly one Tap row, as the test expects; a second attempt any time after about 23:24:12 would very likely have passed.

## 5. 10918: only the test thread stopped

Log lines per 10 s in the test JVM log (`canton_network_test`, same JVM as the splice apps), the Canton log and the
two compose containers; none drops during 23:24:11-23:26:19:

```
cd log/10918/logs-docker-compose-1
for f in canton_network_test.clog.gz canton.clog.gz compose-validator.clog.gz compose-participant.clog.gz; do echo "== $f"; zcat $f | grep -a -o -E '"?@?timestamp"?[:=]"?2026-10-29T23:2[3-7]:[0-9]' | grep -a -o -E 'T23:2[0-9]:[0-9]' | awk '{c[$1]++} END{for(k in c) print k"x "c[k]}' | sort | tr '\n' ' '; echo; done
```
```
== canton_network_test.clog.gz
T23:23:0x 4441 T23:23:1x 4332 T23:23:2x 4171 T23:23:3x 4218 T23:23:4x 4269 T23:23:5x 4170 T23:24:0x 4285 T23:24:1x 4653 T23:24:2x 4649 T23:24:3x 4445 T23:24:4x 4445 T23:24:5x 4445 T23:25:0x 4507 T23:25:1x 4630 T23:25:2x 4302 T23:25:3x 4425 T23:25:4x 4273 T23:25:5x 4319 T23:26:0x 4551 T23:26:1x 5320 T23:26:2x 4617 T23:26:3x 3480 
== canton.clog.gz
T23:23:0x 7341 T23:23:1x 6493 T23:23:2x 6152 T23:23:3x 6206 T23:23:4x 6484 T23:23:5x 6061 T23:24:0x 6502 T23:24:1x 6061 T23:24:2x 6239 T23:24:3x 7480 T23:24:4x 6292 T23:24:5x 6531 T23:25:0x 6305 T23:25:1x 6472 T23:25:2x 6414 T23:25:3x 6334 T23:25:4x 6409 T23:25:5x 6332 T23:26:0x 6525 T23:26:1x 7537 T23:26:2x 6524 T23:26:3x 3037 T23:26:4x 645 
== compose-validator.clog.gz
T23:23:0x 101 T23:23:1x 46 T23:23:2x 42 T23:23:3x 43 T23:23:4x 40 T23:23:5x 44 T23:24:0x 483 T23:24:1x 726 T23:24:2x 757 T23:24:3x 803 T23:24:4x 758 T23:24:5x 761 T23:25:0x 775 T23:25:1x 791 T23:25:2x 746 T23:25:3x 819 T23:25:4x 774 T23:25:5x 750 T23:26:0x 821 T23:26:1x 1238 
== compose-participant.clog.gz
T23:23:0x 614 T23:23:1x 153 T23:23:2x 70 T23:23:3x 59 T23:23:4x 57 T23:23:5x 69 T23:24:0x 418 T23:24:1x 616 T23:24:2x 667 T23:24:3x 675 T23:24:4x 646 T23:24:5x 663 T23:25:0x 729 T23:25:1x 686 T23:25:2x 665 T23:25:3x 762 T23:25:4x 679 T23:25:5x 646 T23:26:0x 847 T23:26:1x 1897
```

The test thread itself logged nothing in between:

```
zcat canton_network_test.clog.gz | grep -a '"thread_name":"ScalaTest-1-running' | awk -F'"' '$4 > "2026-10-29T23:24:11.209" && $4 < "2026-10-29T23:26:19.148"' | wc -l
```
```
0
```

The browser console is relayed into the test log by the Selenium BiDi listener (`FrontendIntegrationTest.scala`
startWebDriver, thread `BiDi Connection`). Matching each console `calling ...` line by its W3C trace parent, the
relay lag from the browser event to the test-log line stays at most 35 ms through the stall, so Firefox, its BiDi
socket and the JVM's BiDi thread were all healthy (`scratch/` is `log/10918/scratch/`):

```
cd log/10918
B=logs-docker-compose-1/browser.org.lfdecentralizedtrust.splice.integration.tests.DockerComposeValidatorFrontendIntegrationTest.frontend.0.1.log.gz; zcat $B | awk -F'\t' '$1>=1793316240000 && $1<=1793316400000 && $4 ~ /log.entryAdded/' | grep -a -o -E '"text":"(wallet|scan-proxy) calling [A-Z]+ [^ ]+ .{0,40}\[00-[0-9a-f]{32}-[0-9a-f]{16}-01\]","timestamp":[0-9]+' | sed -E 's/.*\[00-([0-9a-f]{32})-([0-9a-f]{16})-01\]","timestamp":([0-9]+)/\1-\2 \3/' > scratch/browser-calls.txt
zcat logs-docker-compose-1/canton_network_test.clog.gz | grep -a '"thread_name":"BiDi Connection"' | grep -a ' calling ' | awk -F'"' '$4 > "2026-10-29T23:24" && $4 < "2026-10-29T23:26:40"' | sed -E 's/^\{"@timestamp":"([^"]+)".*\[00-([0-9a-f]{32})-([0-9a-f]{16})-01\].*/\2-\3 \1/' | grep -v '^{' > scratch/testlog-calls.txt
python3 -I lag.py scratch/browser-calls.txt scratch/testlog-calls.txt
```
`lag.py` (kept outside the artifact directory):
```
import sys, datetime, collections
b = {}
for l in open(sys.argv[1]):
    k, t = l.split(); b[k] = int(t)
agg = collections.defaultdict(list)
for l in open(sys.argv[2]):
    k, ts = l.split()
    if k not in b: continue
    t = datetime.datetime.fromisoformat(ts.replace('Z', '+00:00')).timestamp() * 1000
    agg[ts[11:18]].append(t - b[k])
for s in sorted(agg):
    v = agg[s]; print(f"{s}x n={len(v)} min_lag_ms={min(v):.0f} max_lag_ms={max(v):.0f}")
```
Output:
```
23:24:0x n=5 min_lag_ms=0 max_lag_ms=2
23:24:1x n=85 min_lag_ms=0 max_lag_ms=35
23:24:2x n=90 min_lag_ms=0 max_lag_ms=2
23:24:3x n=90 min_lag_ms=0 max_lag_ms=16
23:24:4x n=87 min_lag_ms=0 max_lag_ms=2
23:24:5x n=90 min_lag_ms=0 max_lag_ms=2
23:25:0x n=87 min_lag_ms=0 max_lag_ms=2
23:25:1x n=90 min_lag_ms=0 max_lag_ms=2
23:25:2x n=89 min_lag_ms=0 max_lag_ms=2
23:25:3x n=90 min_lag_ms=0 max_lag_ms=2
23:25:4x n=88 min_lag_ms=0 max_lag_ms=2
23:25:5x n=89 min_lag_ms=0 max_lag_ms=2
23:26:0x n=90 min_lag_ms=0 max_lag_ms=2
23:26:1x n=90 min_lag_ms=0 max_lag_ms=2
23:26:2x n=11 min_lag_ms=1 max_lag_ms=2
```

## 6. 10920: same shape, 208.4 s, in the middle of reading one row

Clue timeline: the tap is clicked at 23:22:47.9, the check starts at 23:22:47.978 and finishes 3.5 min later:

```
zcat log/10920/logs-docker-no-canton-0/canton_network_test.clog.gz | grep -a -E 'Running clue|Failed clue|Finished clue|Test failed' | awk -F'"' '$4 > "2026-10-29T23:22:47.77" && $4 < "2026-10-29T23:26:17.132"' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-10-29T23:22:47.774Z Finished clue: Getting state before tap",
2026-10-29T23:22:47.775Z Running clue: Tapping...",
2026-10-29T23:22:47.775Z Running clue: Waiting for IdQuery(tap-amount-field) to be clickable",
2026-10-29T23:22:47.794Z Finished clue: Waiting for IdQuery(tap-amount-field) to be clickable",
2026-10-29T23:22:47.895Z Running clue: Waiting for IdQuery(tap-button) to be clickable",
2026-10-29T23:22:47.909Z Finished clue: Waiting for IdQuery(tap-button) to be clickable",
2026-10-29T23:22:47.973Z Finished clue: Tapping...",
2026-10-29T23:22:47.973Z Running clue: Making sure the tap has been processed",
2026-10-29T23:22:47.978Z Running clue: Checking for new taps",
2026-10-29T23:26:17.126Z Finished clue: Checking for new taps",
2026-10-29T23:26:17.131Z Failed clue: Making sure the tap has been processed",
```

The browser side of the only attempt: the row lookups and cell reads answered in milliseconds, then nothing from the
test for 208.4 s; the attempt continues with the next cell of the same row:

```
cd log/10920/logs-docker-no-canton-0
B=browser.org.lfdecentralizedtrust.splice.integration.tests.LocalNetFrontendIntegrationTest.frontend.1.1.log.gz; zcat $B | awk -F'\t' '$1>=1793316167900 && $1<=1793316377200 && $2=="Marionette"' | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{20,}/<uuid>/g' | awk -F'\t' '{print strftime("%H:%M:%S",substr($1,1,10),1)"."substr($1,11,3), substr($4,1,140)}' | grep -a 'tx'
```
```
23:22:47.979 0 -> [0,305,"WebDriver:FindElements",{"using":"css selector","value":".tx\\-row"}]
23:22:47.992 0 -> [0,312,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-row\\-cell\\-date"}]
23:22:48.005 0 -> [0,317,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-row\\-cell\\-date"}]
23:22:48.018 0 -> [0,322,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-row\\-cell\\-date"}]
23:22:48.030 0 -> [0,327,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-action"}]
23:22:48.050 0 -> [0,332,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-row\\-cell\\-date"}]
23:22:48.061 0 -> [0,337,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-action"}]
23:22:48.075 0 -> [0,342,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-subtype"}]
23:22:48.089 0 -> [0,347,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-amount\\-amulet"}]
23:26:17.092 0 -> [0,352,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-amount\\-usd"}]
23:26:17.106 0 -> [0,357,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-amount\\-rate"}]
```
```
zcat $B | awk -F'\t' '$1>=1793316168095 && $1<=1793316377100 && $2 ~ /webdriver::server|Marionette/' | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{20,}/<uuid>/g' | awk -F'\t' '{print strftime("%H:%M:%S",substr($1,1,10),1)"."substr($1,11,3), $2, substr($4,1,120)}' | sed -n '7,12p'
```
```
23:22:48.096 webdriver::server -> GET /session/<uuid>/element/<uuid>/text 
23:22:48.096 Marionette 0 -> [0,351,"WebDriver:GetElementText",{"id":"<uuid>"}]
23:22:48.100 Marionette 0 <- [1,351,null,{"value":"+4,000.8 AMT"}]
23:22:48.100 webdriver::server <- 200 OK {"value":"+4,000.8 AMT"}
23:26:17.091 webdriver::server -> POST /session/<uuid>/element/<uuid>/element {
23:26:17.092 Marionette 0 -> [0,352,"WebDriver:FindElement",{"element":"<uuid>","using":"css selector","value":".tx\\-amount\\-usd"}]
```

In `readTapFromRow` the step after reading the amulet amount text is `parseAmountText(..., unit = amuletNameAcronym)`.
LocalNet has no local SV, so `spliceInstanceNames` asks the remote scan
(`CommonAppInstanceReferences.scala:365-389` at 6d404e421b). The test thread's next log line is that call, 208.4 s
after the geckodriver response. The stall is therefore between receiving the `GetElementText` answer and issuing
the scan call. `ConsoleHttpCommandRunner.runCommand` logs `Running on ...` before anything else
(`ConsoleHttpCommandRunner.scala:42-51`):

```
zcat log/10920/logs-docker-no-canton-0/canton_network_test.clog.gz | awk -F'"' '$4 >= "2026-10-29T23:22:47.9" && $4 < "2026-10-29T23:26:17.2"' | grep -a -i -E 'splice-instance|SpliceInstanceNames|scan.localhost|ScalaTest-1-running' | sed -E 's/"logger_name":"([^"]*)".*"thread_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1][\2] \3/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | sed -n '4,10p'
```
```
2026-10-29T23:22:47.978Z Running clue: Checking for new taps",[o.l.s.i.t.LocalNetFrontendIntegrationTest:LocalNetFrontendIntegrationTest][ScalaTest-1-running-LocalNetFrontendIntegrationTest] DEBUG
2026-10-29T23:22:48.017Z Transaction dates after tap: List(2026-10-29T23:22:45Z, 2026-10-29T23:22:45Z)",[o.l.s.i.t.LocalNetFrontendIntegrationTest:LocalNetFrontendIntegrationTest][ScalaTest-1-running-
2026-10-29T23:26:16.504Z Running on scanClient command GetSpliceInstanceNames() against NetworkAppClientConfig(http://scan.localhost:4000,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30
2026-10-29T23:26:17.024Z HTTP client (GET /api/scan/v0/splice-instance-n...): HTTP request took 150 ms to complete. Received response with status code: 200 OK",[o.l.s.i.EnvironmentDefinition$$anon$43:
2026-10-29T23:26:17.116Z Running on scanClient command GetSpliceInstanceNames() against NetworkAppClientConfig(http://scan.localhost:4000,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30
2026-10-29T23:26:17.125Z HTTP client (GET /api/scan/v0/splice-instance-n...): HTTP request took 5 ms to complete. Received response with status code: 200 OK",[o.l.s.i.EnvironmentDefinition$$anon$43:Lo
2026-10-29T23:26:17.126Z New taps: List()",[o.l.s.i.t.LocalNetFrontendIntegrationTest:LocalNetFrontendIntegrationTest][ScalaTest-1-running-LocalNetFrontendIntegrationTest] DEBUG
```

The only attempt saw the two rows of 23:22:45 (the traffic purchase and its earlier tap); the page saved at
23:26:17.176 also has the new Tap of 23:22:47:

```
zcat webpage-26-10-29-23.26.17.176.html.gz | sed 's/></>\n</g' | grep -o -E 'class="[^"]*(tx-row |tx-action|tx-subtype|tx-amount-amulet)[^"]*"[^<]*|>2026-10-29T[0-9:]+Z<' | sed -E 's/class="[^"]*(tx-row |tx-action|tx-subtype|tx-amount-amulet)[^"]*"/\1/' | paste -sd' ' | sed 's/tx-row />\n/g' | cut -c1-150
```
```
>
> tx-action>Balance Change tx-subtype>(Tap) >2026-10-29T23:22:47Z< tx-amount-amulet>+69,120 AMT >
> tx-action>Sent tx-subtype>(Extra Traffic Purchase) >2026-10-29T23:22:45Z< tx-amount-amulet>-4,000.8 AMT >
> tx-action>Balance Change tx-subtype>(Tap) >2026-10-29T23:22:45Z< tx-amount-amulet>+4,000.8 AMT
```

The 10920 JVM kept logging during its stall (other suites of the shard, the BiDi relay, docker-java):

```
zcat log/10920/logs-docker-no-canton-0/canton_network_test.clog.gz | awk -F'"' '$4 > "2026-10-29T23:22:48.1" && $4 < "2026-10-29T23:26:16.5"' | grep -a -o '"thread_name":"[^"]*"' | sed -E 's/-[0-9]+"$/-N"/' | awk '{c[$0]++} END{for(k in c) print c[k]" "k}' | sort -rn | head -4
```
```
4093 "thread_name":"BiDi Connection"
2235 "thread_name":"docker-java-stream-N"
120 "thread_name":"ScalaTest-dispatcher"
36 "thread_name":"S3UploadTest-test-execution-context-N"
```

## 7. What the stall is not

Not a Selenium HTTP read that timed out: selenium-java 4.44.0 (build.sbt at 6d404e421b) defaults to a 180 s read
timeout, the repo does not override it, 10920 stalled 208 s, and neither log has a timeout or a `Retrying`
WebDriverException line:

```
git grep -n -i 'selenium' 6d404e421b -- build.sbt | sed -n '5p'
javap -p -c -cp ~/.cache/coursier/v1/https/repo1.maven.org/maven2/org/seleniumhq/selenium/selenium-http/4.44.0/selenium-http-4.44.0.jar org.openqa.selenium.remote.http.ClientConfig | grep -A3 'private static java.time.Duration defaultReadTimeout'
git grep -n -E 'webdriver\.httpclient|readTimeout' 6d404e421b -- build.sbt project/ .github/ apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala | wc -l
for d in 10918/logs-docker-compose-1 10920/logs-docker-no-canton-0; do zcat log/$d/canton_network_test.clog.gz | grep -a -c -E 'Retrying (TimeoutException|WebDriverException|UnreachableBrowserException)|HttpTimeoutException'; done
```
```
6d404e421b:build.sbt:2446:      libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "4.44.0" % "test",
  private static java.time.Duration defaultReadTimeout();
    Code:
         0: ldc           #103                // String webdriver.httpclient.readTimeout
         2: ldc           #105                // String 180
0
0
0
```

No name-resolution errors in the 10918 window; the only hits are the compose restart before 23:22:14 (containers not
up yet):

```
zcat log/10918/logs-docker-compose-1/compose-validator.clog.gz | grep -a -i -E 'UnknownHost|Name or service not known|Temporary failure in name resolution' | grep -a -o -E '"@?timestamp"?[:=]"?[0-9T:.-]+' | sed -E 's/.*"?(2026[^"]*)$/\1/' | sed -n '1p;$p'
zcat log/10918/logs-docker-compose-1/canton.clog.gz log/10918/logs-docker-compose-1/canton_network_test.clog.gz | grep -a -i -c -E 'UnknownHost|Name or service not known|Temporary failure in name resolution'
```
```
2026-10-29T23:21:59.097
2026-10-29T23:22:13.591
0
```

Not stdout back-pressure: the job logs show the runner reading the sbt output every 30 s through both stalls (`Test
still running after ...`), e.g. 10920:

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10920/job.log | awk '$1 >= "2026-10-29T23:24" && $1 < "2026-10-29T23:26:20"' | cut -c12-23,29-95 | head -4
```
```
23:24:04.093 [info] *** Test still running after 6 minutes, 27 seconds: suite n
23:24:04.093 [info] Test still running after 6 minutes, 25 seconds: suite name:
23:24:34.093 [info] *** Test still running after 6 minutes, 57 seconds: suite n
23:24:34.093 [info] Test still running after 6 minutes, 55 seconds: suite name:
```

## 8. The two stalls end together on different runners

```
for j in 123782957691 123783162222; do gh api repos/canton-network/splice/actions/jobs/$j --jq '"\(.id) \(.name) runner=\(.runner_name) start=\(.started_at) end=\(.completed_at)"'; done
```
```
123782957691 ci / scala_test_docker_compose / docker-compose (1) runner=self-hosted-docker-large-runner-ppddx start=2026-10-29T22:59:29Z end=2026-10-29T23:27:06Z
123783162222 ci / scala_test_with_docker_no_canton / docker-no-canton (0) runner=self-hosted-docker-large-runner-jsb54 start=2026-10-29T23:00:32Z end=2026-10-29T23:32:26Z
```

Stall windows: 10918 23:24:11.209-23:26:19.148 (sections 3, 5), 10920 23:22:48.100-23:26:16.504 (section 6). Started
83 s apart, ended 2.6 s apart. Only failed jobs upload artifacts, so the other docker-large job of the run
(docker-compose (0), runner fftcz, tests done 23:23:25, passed) cannot be checked for a stall; its job log shows no
event at 23:26:16-19. The shared trigger, if there is one, is not identified.

## 9. Recurrence

```
for q in '"LocalNetFrontendIntegrationTest"' '"docker-compose (1)" created:>=2026-10-29' '"docker-no-canton" created:>=2026-10-29'; do echo "## $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q" --jq '.items[] | "\(.number) \(.state) \(.created_at[0:10]) \(.title)"' | head -12; done
```
Hits after 10-29 and why they are not this failure (from `evidence/INDEX.tsv` and the round files):
- 10968 10970 10971, 11032-11037, 11068-11070, 11081 11084 11102 (10-30): bulk duplicates closed in
  `rounds/2026-10-30T112522Z/host-commands*.md` (main break / credential break of that day), not a tap-row check.
- 11198 11199 11201 11215 (11-05, docker-compose (1)): Auth0 `access_denied` credential break
  (`rounds/2026-11-05T094500Z-credentials/README.md`).
- 11279 (11-08, docker-no-canton): Testcontainers pull watchdog ERROR, not a frontend check.
- 10901 10902 10909 (10-29, before this run): Auth0 secret rotation.
No other occurrence of a frontend check failing after a single attempt; 10918 + 10920 is one run, 12 days ago.

## Verdict

- New, parent 10918 (10920 already closed as its duplicate). Family AT (new): frontend test thread stalls for
  minutes inside the only `eventually` attempt.
- Flake. The wallet, the backend and the compose setup behaved correctly: the tx list loaded 0.3 s after the
  check started (10918) and the new tap was on the page at failure (both).
- Mechanism shown: one WebDriver read answered within milliseconds, then the test thread made no call for
  127.9 s / 208.4 s while the JVM, browser and BiDi relay ran normally; `BaseTest.eventually` (vendored canton test
  code) does not retry after the deadline, so the stale first read is reported.
- Not shown: where the test thread blocked (no thread dump; not a Selenium read timeout, not DNS, not stdout), and
  why both stalls ended within 2.6 s on two runners.
- Fix: none proposed for one run in 12 days. Possible hardening if it recurs: give `FrontendTestCommon.eventually`
  one more attempt when the deadline passed during an attempt (a stalled attempt then no longer decides the
  result), and log a stack dump of the test thread when one attempt exceeds the budget, so the next occurrence shows
  where it blocks.
- Status suggestion: comment now; `Infrequent / no repro` from 2026-11-12 (two weeks) if nothing recurs.
