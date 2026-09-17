# 10155 and 10158 - ACS_COMMITMENT_MISMATCH sv1Participant vs aliceValidator right after an expiry suite multi-hosts alice on sv1 (runs 35115412224, 35123371703)

Both are DUPLICATES of 10146 (run 35077158925; earlier 10129 / run 34838594625 and run 34523566111). The
mechanism in the 10146 packet section 10 predicts exactly these: the first commitment period after
`Multi-host alice on sv1Participant` (no ACS import) mismatches between sv1Participant and aliceValidator.

| ref | run / job | branch, sha | canton | multi-host clue | period (fromExclusive, toInclusive] | WARN received |
|-----|-----------|-------------|--------|-----------------|-------------------------------------|---------------|
| 10155 | 35115412224 / 104861312251 `wall-clock-time (5)` | main ed12df6164 (#7348) | 3.6.0-snapshot.20260910.20260.0.v90621933 | 15:58:11.760 (ExpiryWithIgnoredAmuletVersionIntegrationTest), 15:59:17.409 (AutoIgnoreUnresponsivePartiesInMemoryIntegrationTest) | (15:59:21.460848, 16:00:00] = 38.5 s | 16:03:42.152 |
| 10158 | 35123371703 / 104886784003 `wall-clock-time (1)` | main 2b3e9d21ae (#7352) | same | 16:59:14.132 (ExpiryWithIgnoredAmuletVersionIntegrationTest) | (16:59:27.307259, 17:00:00] = 32.7 s | 17:04:19.750 |

All tests passed in both shards (20 and 28); the only `checkErrors` problem is the one WARN each.

## 1. The flagged line (job consoles; identical shape in both)

```
grep -a -B300 'contains problems' log/10155/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line'
```
```
16:03:42.152Z WARN c.d.c.p.c.ReceivedAcsCommitmentMatcher:participant=sv1Participant/synchronizer=global-domain::12207158641d
  ACS_COMMITMENT_MISMATCH(5,bc069b84): The local commitment does not match the remote commitment
  remote: sender = aliceValidator::122078010dec..., counterparticipant = sv1::12200628f3a2...,
          period = CommitmentPeriod(fromExclusive = 2026-09-16T15:59:21.460848Z, toInclusive = 2026-09-16T16:00:00Z), digest = 1220951111d2...
  local:  LocalDigest(period = same, digest = 1220a1d496a5...)
```
10158: same logger, sender aliceValidator::1220e18291f0..., period (16:59:27.307259Z, 17:00:00Z], remote digest
1220c1817adc..., local 1220b22f9445..., received 17:04:19.750Z.

## 2. Suite timeline around each period

```
zcat log/10155/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test succeeded|Multi-host alice" | grep -a 'T1(5:5|6:0)'
```
```
15:57:34.676Z Starting test suite 'ExpiryWithIgnoredAmuletVersionIntegrationTest'
15:58:11.760Z Running clue: (act) Multi-host alice on sv1Participant (alice keeps her old host)
15:58:49.686Z Test succeeded: 'ExpiryWithIgnoredAmuletVersionIntegrationTest/Expiry triggers skip parties whose preferred amulet package version is ignored'
15:58:49.692Z Starting test suite 'AutoIgnoreUnresponsivePartiesInMemoryIntegrationTest'
15:59:17.409Z Running clue: (act) Multi-host alice on sv1Participant
16:00:18.513Z Test succeeded: 'AutoIgnoreUnresponsivePartiesInMemoryIntegrationTest/Expiry triggers auto-ignore parties whose participant is disconnected (MEDIATOR_SAYS_TX_TIMED_OUT)'
```
The mismatched period starts 4 s after the second multi-host step.

```
zcat log/10158/logs-wall-clock-time-1/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test succeeded|Multi-host alice" | grep -a 'T1(6:5|7:0)'
```
```
16:58:30.978Z Starting test suite 'ExpiryWithIgnoredAmuletVersionIntegrationTest'
16:59:14.132Z Running clue: (act) Multi-host alice on sv1Participant (alice keeps her old host)
16:59:52.312Z Test succeeded: 'ExpiryWithIgnoredAmuletVersionIntegrationTest/...'
16:59:52.321Z Starting test suite 'TokenStandardCliIntegrationTest'
```
The mismatched period starts 13 s after the multi-host step.

The 3.7 and 4.4 minute gaps between period end and WARN are the randomised commitment send delay
described in the 10146 packet; the WARN lands in whatever suite runs later (WalletTxLogAcs, FeaturedApp
ActivityMarker), which is why the flagged shard's suite list looks unrelated.

## 3. Multi-host sites (unchanged since 10146)

```
grep -n 'Multi-host alice on sv1Participant' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/*.scala
```
```
ExpiryWithMinimalVettedPackagesIntegrationTest.scala:380   (base of AmuletExpiryV1Fallback / ExpiryWithIgnoredAmuletVersion / ExpiryWithNoVettedAmuletVersion)
AutoIgnoreUnresponsivePartiesIntegrationTest.scala:89
```

## 4. Verdict

Four occurrences now (34523566111, 10129, 10146, 10155, 10158) with the same (sv1Participant, aliceValidator)
pair and always the first period after a multi-host step. Fix the four test suites (host alice on sv1 before
she owns contracts, or replicate the party with its ACS); do not widen `canton_log.ignore.txt:145`.
