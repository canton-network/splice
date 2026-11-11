# g7-misc result (11357 11365 11366 11367)

Packets:
- `evidence/11357-sim-jump-timeouts-trip-sv2-breaker-dup-11147.md`
- `evidence/11365-release-line-0.9.4-cold-compile-40m-dup-10358.md`
- `evidence/11366-11367-runner-network-blip-2026-11-11.md` (both refs)

Comment bodies: `comments/11366.md`, `comments/11367.md` (11357 and 11365 are inline duplicate closes).

## 11357 - duplicate of 11147 (AF)

Verdict: duplicate of 11147, family AF, fourth hit (sv2 again). Confirming check holds: after the last sv2 medium
success at 11:56:30.488, 21 medium-trigger LOCAL_VERDICT_TIMEOUT failures over the 5 sim-clock jumps 11:56:31.362 to
11:56:39.606, trip at 11:56:40.155 (the last four failures are within 4 ms of the trip, so which one was the 20th
counted is not decidable). Tests 5/5 passed, attempt 2 passed. Fix PR splice#8420 still open.

Action:
```
direnv exec . ci-triage/scripts/close_duplicates.py 11147 11357 --comment 'Same sv2 medium-priority circuit breaker trip during the hourly sim-clock steps of ScanNewNetworkBackfillingTimeBasedIntegrationTest: 21 LOCAL_VERDICT_TIMEOUT failures of the medium triggers over 5 steps with no medium success in between; all tests passed, only checkErrors failed.'
```
Assignee: none (parent assigned to stephencompall-DA).

INDEX row:
```
11357	2026-11-11	43012738610	main 2d54198 (#8427)	130496469862 docker-canton-simtime (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	ScanNewNetworkBackfillingTimeBasedIntegrationTest sv2 medium breaker tripped 11:56:40.155 after 21 medium LOCAL_VERDICT_TIMEOUT over 5 sim-clock steps (11:56:31-11:56:40), last medium success 11:56:30.488; tests passed, attempt 2 passed	11147 (AF)		close dup 11147; covered by fix PR splice#8420 (open)	evidence/11357-sim-jump-timeouts-trip-sv2-breaker-dup-11147.md
```

known-families.md, AF "Hits" line, append:
`11357 (2026-11-11, main 2d54198, 5 steps, dup, sv2).`

Lesson: none.

## 11365 - duplicate of 10358 (J cold compile, release line; E missing backport of #8319)

Verdict: not a test failure. release-line-0.9.4 missed the precompiled-classes cache (as every release-line run does),
sbt compiled 15:08:38 to 15:23:25 (15 min), tests ran normally (new suites up to 15:41:54) and sbt was killed by the
40 min timeout at 15:48:20 (`Killed SBT after timeout 40m`, no Tests line). Passing wall-clock-time (5) runs on 0.9.4
of 11-05 used 35 and 39 of the 40 min (compile 12 min + tests 23 to 26 min). splice#8319 (Test/compile in its own
step) is on main only; no backport PR. The run shows `cancelled` because check_canton_consistency hit its 15 min job
timeout in Setup Nix, unrelated. Third hit on release-line-0.9.4 (11212, 11231, 11365; 11208 on 0.9.x), all closed as
dup of 10358, which is closed.

Action:
```
direnv exec . ci-triage/scripts/close_duplicates.py 10358 11365 --comment 'Killed SBT after timeout 40m on release-line-0.9.4 after a classes cache miss: compile 15:08-15:23, tests then ran out of time. Same as 11212 and 11231; the fix on main, splice 8319, is still not backported to the release lines.'
```
Open point for the main thread (unanswered since 2026-11-09): backport splice#8319 (5c8e6ffcfa) to
release-line-0.9.4 (and the other active release lines), or open a tracking issue; every release-line run with a cold
compile can hit this. Assignee: none (no evidence of an owner beyond #8319's author ray-roestenburg-da, per family J).

INDEX row:
```
11365	2026-11-11	43043473742	release-line-0.9.4 62f3512425 (#8438)	130601863599 wall-clock-time (5)	3.6.4	Killed SBT after timeout 40m at 15:48:20, no Tests line; classes cache miss, compile 15:08:38-15:23:25 (15 min), tests progressing until the kill; passing 0.9.4 runs use 35-39 of 40 min; #8319 not on 0.9.4; run cancelled by check_canton_consistency 15 min timeout (unrelated)	10358 (J, E)		close dup 10358; #8319 backport to release lines still untracked	evidence/11365-release-line-0.9.4-cold-compile-40m-dup-10358.md
```

known-families.md, J, release-line paragraph ("Release-line runs never hit the classes cache ..."), append:
`11365 (2026-11-11, release-line-0.9.4 62f3512, wall-clock-time (5), compile 15 min, killed 15:48:20): third 0.9.4 hit after 11212 and 11231; passing 0.9.4 wall-clock-time (5) runs of 11-05 used 35 and 39 min of the 40.`

Lesson: none.

## 11366 - new, infra one-off (runner k8s hook socket hang up)

Verdict: docs built and uploaded; the job failed in Post-SBT `Store Daml artifacts` (actions/cache/save through the
runner's k8s container hook) with `##[error]Error: socket hang up` at 16:24:03, 59 s after the step started and with
no output of the action itself. Same window as 11367 and two more `socket hang up` hook failures in run 43054307546
(16:05:31, 16:06:20, no issues filed). No GitHub incident. Likely a short runner-cluster network problem. The hook
retry from 8613 (runner-container-hooks 8472c0f, the splice pin) retries only ETIMEDOUT and HTTP 429/5xx.

Action:
```
gh issue comment 11366 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T210621Z-inbox/comments/11366.md
gh issue close 11366 -R DACH-NY/cn-test-failures --reason 'not planned'
```
Assignee: none. Optional: mention to the 8613 owner (jagathweerasinghe-da) that ECONNRESET / `socket hang up` is not
in the retry pattern; not proposed as an action (which hook call raised it is not shown).

INDEX row:
```
11366	2026-11-11	43055359204	main 1ee8c88 (#8434)	130643238246 docs	3.7.3-snapshot.20261109.20894.0.ve6e6c252	docs built and uploaded; Post-SBT Store Daml artifacts (actions/cache/save via the k8s container hook) failed 16:24:03 with Error: socket hang up after 59 s; same window 16:05-16:24 as 11367 and two more hook socket hang ups in run 43054307546; no GitHub incident	new (J)		infra one-off; comment and close not planned	evidence/11366-11367-runner-network-blip-2026-11-11.md
```
(canton cell from `git show <sha>:nix/canton-sources.json | grep -m1 version`, same pin at 1ee8c88 and 3b5b828.)

## 11367 - new, infra one-off (runner DNS, apt in docker builds)

Verdict: `Build Docker images` (`make docker-build -j8`, cmd_retry_count 5) failed all 6 attempts 16:08:04 to
16:24:23: apt inside the image builds got `Temporary failure resolving` for archive.ubuntu.com, security.ubuntu.com,
ppa.launchpadcontent.net (about 500 lines) and `Could not connect to ppa.launchpadcontent.net:443 ... connection timed
out`; failing image splice-debug, then splice-test-docker-runner; archive.ubuntu.com resolved in between (16:10:17).
Same 20 min window as 11366. No GitHub incident; no earlier issue with `Temporary failure resolving`.

Action:
```
gh issue comment 11367 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T210621Z-inbox/comments/11367.md
gh issue close 11367 -R DACH-NY/cn-test-failures --reason 'not planned'
```
Assignee: none.

INDEX row:
```
11367	2026-11-11	43054307546	main 3b5b828 (#8432)	130640012483 docker-no-canton (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	Build Docker images (make docker-build, 6 attempts 16:08:04-16:24:23) failed: apt in image builds Temporary failure resolving archive/security.ubuntu.com and ppa.launchpadcontent.net, intermittent (archive resolved 16:10:17), failing image splice-debug then splice-test-docker-runner; same window as 11366 and two hook socket hang ups in this run; no GitHub incident	new (J)		infra one-off; comment and close not planned	evidence/11366-11367-runner-network-blip-2026-11-11.md
```

known-families.md, J, new bullet:
`- Runner network blip 2026-11-11 16:05-16:24 UTC (11366, 11367): four jobs on four self-hosted runners of two main
  runs: k8s container hook ##[error]Error: socket hang up (docs Post-SBT cache save, and Initialize containers /
  checkout in two jobs without issues), and apt DNS failures (Temporary failure resolving archive.ubuntu.com etc.) in
  docker-no-canton Build Docker images, all 6 make retries. No GitHub incident. The hook retry for 8613
  (runner-container-hooks 8472c0f) matches only ETIMEDOUT and HTTP 429/5xx. Confirm: same error in other jobs
  of the window. One-off; close not planned.`

Lesson: none (infra one-offs).
