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

## 5. 10162 (run 35206251191, 2026-09-17) - sixth occurrence, same shape

- Run: https://github.com/canton-network/splice/actions/runs/35206251191, main 22e775d614 (#7363 "set pg max
  freeze age to 500mil"), job 105152950685 `wall-clock-time (1)`, canton 3.6.0-snapshot.20260910.20260.0.v90621933.
  All 23 tests pass; one WARN.

```
grep -a -B400 'contains problems' log/10162/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line'
```
```
10:03:16.243Z WARN ReceivedAcsCommitmentMatcher:participant=sv1Participant  ACS_COMMITMENT_MISMATCH(5,a2e67a5e)
  sender = aliceValidator::12203bec954e..., period = (2026-09-17T09:59:53.894149Z, 2026-09-17T10:00:00Z]
  remote digest 12207e0c947a..., local digest 1220ba2224b7...
```
```
zcat log/10162/logs-wall-clock-time-1/canton_network_test.clog.gz | grep -a -E "Starting test suite|Multi-host alice" | grep -a 'T09:5[89]'
```
```
09:58:06.786Z Starting test suite 'ExpiryWithIgnoredAmuletVersionIntegrationTest'
09:58:43.414Z Running clue: (act) Multi-host alice on sv1Participant (alice keeps her old host)
09:59:19.069Z Starting test suite 'AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest'
09:59:50.308Z Running clue: (act) Multi-host alice on sv1Participant
```
The mismatched period starts 3.6 s after the second multi-host step (the WithPersistence variant this time;
same `AutoIgnoreUnresponsivePartiesIntegrationTest.scala:89` code). WARN delivered 3.3 min later during
UnvetAllSupportedPackagesIntegrationTest.

## 6. 10164 (run 35222005752, 2026-09-17) - seventh occurrence, first on canton 3.6.0-snapshot.20260916

- Run: https://github.com/canton-network/splice/actions/runs/35222005752, main d7a75f6e9e (#7346), job 105204675967
  `wall-clock-time (6)`, canton 3.6.0-snapshot.20260916.20284.0.vf27c4824. 39 tests pass; one WARN.
```
13:01:47.078Z WARN ReceivedAcsCommitmentMatcher:participant=sv1Participant  ACS_COMMITMENT_MISMATCH(5,c6aaa3ff)
  sender = aliceValidator::12207ee73d49..., period = (2026-09-17T12:59:58.296222Z, 2026-09-17T13:00:00Z]
```
```
12:59:20.802Z Starting test suite 'AmuletExpiryV1FallbackIntegrationTest'
12:59:55.748Z Running clue: (act) Multi-host alice on sv1Participant (alice keeps her old host)
13:00:30.504Z Test succeeded: 'AmuletExpiryV1FallbackIntegrationTest/...'
```
Period starts 2.5 s after the multi-host step. Same suite as 10146 itself.

## 7. 10111 is run 34523566111 (2026-09-10), the earliest recorded occurrence

Ref 10111 = https://github.com/canton-network/splice/actions/runs/34523566111 wall-clock-time (1), main 4031327bc4
(#7176), the run already listed in the 10146 packet's occurrence table: period (20:29:33.037, 20:30:00], 27 s,
sv1Participant vs aliceValidator, ExpiryWithNoVettedAmuletVersionIntegrationTest in the shard. Same mechanism as
all later ones (10129, 10146, 10155, 10158, 10162, 10164). Chronologically 10111 is the parent; whichever issue is
kept, the fix is the multi-hosting in ExpiryWithMinimalVettedPackagesIntegrationTest.scala and
AutoIgnoreUnresponsivePartiesIntegrationTest.scala.

## 8. 10167 (run 35237977178, 2026-09-17) - eighth occurrence

- Run: https://github.com/canton-network/splice/actions/runs/35237977178, main e6689c46e7, job 105259186280
  `wall-clock-time (1)`, canton 3.6.0-snapshot.20260916.20284.0.vf27c4824. 26 tests pass (4 ignored); one WARN.
```
15:30:07.126Z WARN ReceivedAcsCommitmentMatcher:participant=sv1Participant  ACS_COMMITMENT_MISMATCH(5,e51fe2c2)
  sender = aliceValidator::1220eb494657..., period = (2026-09-17T15:29:24.158920Z, 2026-09-17T15:30:00Z]
```
```
15:28:44.621Z Starting test suite 'ExpiryWithNoVettedAmuletVersionIntegrationTest'
15:29:21.446Z Running clue: (act) Multi-host alice on sv1Participant (alice keeps her old host)
15:29:45.778Z Test succeeded: '.../Amulet expiry ignores parties with no vetted amulet version'
```
Period starts 2.7 s after the multi-host step; WARN delivered 7 s after period end (short random delay this time).
Side note in the same job: the artifact upload step also errored on a file name containing a party id with `::`
(`alice-validatorcfbce084-1::1220eb494657...acs`, "Colon :" not allowed), unrelated to the failure but it means
one ACS dump file is missing from the artifact.
