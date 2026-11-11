# 11349: RateLimitPreflightIntegrationTest, extra ACS call after the 2/s bucket refilled (svda1Scan)

Issue: DACH-NY/cn-test-failures#11349, CircleCI pipeline 88699, workflow deploy_lsu_operator, job 531054,
branch build/ciperiodic-lsu (cni d0869c8 "Update config to retire the old physical synchronizer on ciperiodic").
Evidence: step excerpt pasted by the user; `canton_network_test.clog.gz` of the job saved by the user to
`export/` (copied to `log/11349/`). Triaged 2026-11-11 in the main thread of round 2026-11-11T074142Z-inbox.

## Failure (user paste of the step output)

```
[info] - Scan ACS requests are rate limited *** FAILED ***
[info]   TestFailedException was thrown during property evaluation. (RateLimitPreflightIntegrationTest.scala:32)
[info]     Message: Expected exception java.lang.Throwable to be thrown, but no exception was thrown
[info]     Location: (RateLimitPreflightIntegrationTest.scala:86)
[info]     Occurred at table row 2 (zero based, not counting headings), which had values (
[info]       scan = Scan Client 'svda1Scan'
```

Line 86 is the final `assertThrowsAndLogsCommandFailures(call, ... "Too Many Requests")`: one extra ACS call after
the burst of 20 (`collectResponses(limit = 20, ...)`, parallelism 32) is expected to be refused.

## The limit: scan app limiter, 2 per second

`cluster/images/scan-app/app.conf` (origin/main), unchanged since #6933 (2026-09-01) / #7112:

```
          rate-limiters {
            getAcsSnapshot {
              rate-per-second = 2
              sustained-rate-per-second = 1
            }
```

Not the Envoy filter: the pulumi `externalRateLimits` buckets are 10000/1000 per 10 s (cluster/configs/shared/base.yaml:309).

## Per-scan responses in the test (clog lines 4136-4326)

Command:
```
zcat log/11349/canton_network_test.clog.gz | sed -n '4136,4326p' | awk '<segment by "Running on <scan> command GetDsoPartyId", count GET /api/scan/v0/acs status 200/429>'
```
Output:
```
sv1Scan (https://scan.sv-2.ciperiodic.network.canton.global 200=2 429=19 other=0
sv2Scan (https://scan.sv-2-eng.ciperiodic.network.canton.global 200=2 429=19 other=0
svda1Scan (https://scan.sv-1.ciperiodic.network.canton.global 200=3 429=18 other=0
```

So the limit is enforced on all three scans (2 of the 20 burst calls pass everywhere). The difference is only the
extra call.

## Timing of the extra call

sv1Scan: burst sent 01:06:23.768-769, extra call 01:06:23.961 (0.19 s later), answered 429 at 01:06:23.974.

svda1Scan (verbatim, shortened):
```
{"@timestamp":"2026-11-11T01:06:24.118Z","message":"Running on svda1Scan command GetAcsSnapshot(rate-limit-party::1220cc5ec5e4...,None) against NetworkAppClientConfig(https://scan.sv-1.ciperiodic.network.canton.global,...
{"@timestamp":"2026-11-11T01:06:24.226Z","message":"HTTP client (GET /api/scan/v0/acs/rate-limit-pa...): HTTP request took 106 ms to complete. Received response with status code: 200 OK",...
{"@timestamp":"2026-11-11T01:06:25.268Z","message":"Running on svda1Scan command GetAcsSnapshot(rate-limit-party::1220cc5ec5e4...,None) against NetworkAppClientConfig(https://scan.sv-1.ciperiodic.network.canton.global,...
{"@timestamp":"2026-11-11T01:06:25.290Z","message":"HTTP client (GET /api/scan/v0/acs/rate-limit-pa...): HTTP request took 19 ms to complete. Received response with status code: 200 OK",...
{"@timestamp":"2026-11-11T01:06:25.296Z","message":"Test failed: 'RateLimitPreflightIntegrationTest/Scan ACS requests are rate limited', ...
```
20 calls at 01:06:24.118 (`uniq -c` on their timestamps: 20 at 24.118, 1 at 25.268). The extra call left 1.15 s after
the burst; with 2 per second the bucket had refilled, so it got 200 and line 86 failed.

## Not verified

Why the test thread took 1.04 s between the last burst response (24.226) and the extra call (25.268) on svda1 but
0.06 s on sv1: the clog has no line in between (the work there is the log assertion and `forAtLeast`). A client-side
pause (GC, CPU) is plausible, not shown.

## Family and duplicates

No family. Earlier RateLimitPreflight issues (16 hits, all closed: 9717, 8726, 5866, 6581, 6268, ...) failed on the
error-message match or on 429s, not on a missing 429. First hit of this shape.

## Fix (test)

The test already has the evidence it needs in the burst: 18-19 of the 20 calls are refused with `429 Too Many
Requests`. Assert `forAtLeast(1, results)` is a Failure whose message includes "Too Many Requests" and drop the
separate timed call at line 86 (or put the extra call into the burst). That removes the dependence on sub-second
timing against a 2/s limit. Owner suggestion: nicu-da (rewrote the test in #6933 together with these limits).

Lesson candidate: asserting that a rate limit refuses "the next call" races the refill; assert on the burst itself.
