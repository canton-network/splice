# g4-keydl: 11347

Verdict: new (first hit), test flake on a slow runner. SvReonboardingIntegrationTest: sv1 SvApp init exceeded the 5 min
startAllSync wait (190 s of DAR uploads, 22 of 87 repeats as in 11299 / AM); teardown threw in
UpdateHistorySanityCheckPlugin (sv2Scan running but without app state), environment.close() skipped (family H
cascade), the next 5 tests failed at Creating fixture (Prometheus port in use). key-download (0) passed in the 22
other main runs since 2026-11-09 14:57.

Packet: evidence/11347-sv-reonboarding-sv1-init-over-5min-teardown-cascade.md
Comment: comments/11347.md

INDEX.tsv row:
11347	2026-11-11	42923829975	main 2ef0423979 (#8356)	130205489387 key-download (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	SvReonboardingIntegrationTest sv1 init over the 5 min startAllSync wait (start 00:26:59.9, DAR uploads 00:28:22.9-00:31:32.8, 87 uploads 22 repeats, mean 7.5 s; timeout 00:32:04.6); teardown UpdateHistorySanityCheckPlugin threw on sv2Scan without app state, environment leaked, 5 later tests failed at Creating fixture (Prometheus BindException)	new (AM-related init time; H teardown cascade)		comment, retitle, Triaged; fix: plugin filter on app health + 11299 dedup	evidence/11347-sv-reonboarding-sv1-init-over-5min-teardown-cascade.md

known-families.md text:
- Under AM, add: `- 11347 (2026-11-11, key-download (0), main 2ef0423979): sv1 (founding SV) init in
  SvReonboardingIntegrationTest exceeds startAllSync's 5 min waitForInitialization; 87 DAR uploads (22 repeats) took
  190 s at 7.5 s mean. Confirm: `Timeout while waiting for initialization of sv1` while the sv1 log shows
  `Sending upload dar for` or the DsoRules bootstrap still running.`
- Under H, teardown leak cascade, add: `- 11347 (2026-11-11): UpdateHistorySanityCheckPlugin filters scans by
  is_initialized (node running) and then calls scan.automation, which needs the app state; a scan still initializing
  (sv2Scan in Get primary party, waiting for sv1) throws `Node doesn't have any app state` and the cascade follows.
  Fix candidate: filter on app health active; or run beforeEnvironmentDestroyed inside the try (canton change).`

Lesson candidate: "Teardown must tolerate a partially started environment": a cleanup hook that assumes every running
node finished initializing throws exactly when a test already failed during startup, and if the hook runs before
the resource close, the leak (ports, threads) fails every later test in the JVM. Check readiness with the same
predicate the code you call needs (app state, not node started), and run hooks so that close() always happens.

Suggested action (commands for the user):
gh issue comment 11347 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T074142Z-inbox/comments/11347.md
gh issue edit 11347 -R DACH-NY/cn-test-failures --title 'SvReonboardingIntegrationTest: sv1 init exceeds the 5 min startAllSync wait, teardown leak fails the rest of key-download'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11347

No assignee: plugin lines have several authors (git blame), no introducing PR.
