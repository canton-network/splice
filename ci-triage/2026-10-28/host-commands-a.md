# Host commands, 2026-10-28 round a (nightly 40635877843 per-job dups of 10520, 10697)

Run on the host (zsh). 10520 stays open; it still needs an owner (5th red nightly).

```zsh
gh issue close 10702 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10703 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10704 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10705 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10706 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10707 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10708 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10709 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10710 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10711 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10712 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10713 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10714 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10715 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10716 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10717 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10718 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10719 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10720 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10722 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue close 10723 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10520"
gh issue comment 10520 --repo DACH-NY/cn-test-failures --body "Still failing: nightly 2026-10-28 (run 40635877843, main dee813861c), every shard INITIAL_ONBOARDING_ERROR in SV1Initializer, sequencer TOPOLOGY_SERIAL_MISMATCH (expected 2, actual 1) on the ::36-0 synchronizer. Per-job issues 10702-10720, 10722, 10723 closed as dups."
gh issue close 10697 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10358. #8003 changed daml/dars.lock (part of the classes cache key), so the first main run on dee813861c missed the precompiled-classes cache, compiled from scratch (22:54-23:06) and hit Killed SBT after timeout 40m while the 8th of 9 suites ran. No test failed."
```
