# g2-rflsu result (11371, 11372)

Packet: `ci-triage/evidence/11371-11372-rollforward-lsu-pre-dr-cutoff-dup-11303.md`

## Verdict per ref

- 11371 (run 43072152132, job 130703038018, main 08774bc437, Canton 3.7.3-snapshot.20261109): DUPLICATE of 11303
  (family AK, 11312/11328 shape). The 5 min wait is `waitForInitialization` of sv3 (SvAppReference) in "Start nodes
  before DR". sv3 sends its last join step `DsoRules_SetSynchronizerNodeConfig` at 18:09:42.180. That is 4.4 s after
  the pre-DR bound 18:09:37.796976 (suite start + 300 s). Every send after the bound is refused with
  `SEQUENCING_TIME_NOT_ADMISSIBLE` (1584 lines). The fourth sequencer joined the ordering topology at 18:09:28.7.
- 11372 (run 43073759004, job 130709572782, main 915616a77d, Canton 3.7.3-snapshot.20261111): DUPLICATE of 11303
  (family AK, new delay variant). The 5 min wait is `waitForInitialization` of sv2Scan, which waits for the DSO party
  of sv2. sv2, sv3 and sv4 join in parallel and late. The 2-node ordering topology (sv1, sv3; strong quorum 2) is
  active from 18:20:27.66, but the sv3 BFT node only starts its onboarding state transfer at 18:21:00.7 and joins epoch
  40 at 18:21:08, so nothing is ordered for 39 s. sv2 sends `add sv to Dso` at 18:20:30.85, 26 s before the bound
  18:20:56.897578. It gets sequencing time 18:21:08.28 and is refused.
- Both suites ABORT in teardown (`ShutdownFailedException: Unable to close ... SpliceEnvironment`). That is a
  consequence: the in-flight join commands of the uninitialized SVs (`Active readers: add sv to Dso`).
- Not hard broken: `roll-forward-lsu (0)` failed in 4 of 34 jobs on the 32 post-merge main runs of 2026-11-10/11
  (11328, 11336, 11371, 11372). No two consecutive runs failed. 3b5b82822d (#8432), 1ee8c88066 and ee8772cc0b (Canton
  bump #8441, the parent of 915616a77d) passed, and so did the next run d356bcf573. There is no first failing sha.
- #8432 is not involved. Neither run has `NO_TEMPLATES_FOR_PACKAGE_NAME_AND_QUALIFIED_NAME`, and the new
  FeaturedAppConfigUpdateInstruction triggers log no WARN or ERROR. H2 does not apply: no validator restart, and the
  failure is before the LSU.

## INDEX.tsv rows

```
11371	2026-11-11	43072152132	main 08774bc437 (#8442)	130703038018 roll-forward-lsu (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	RollForwardLsuDRIntegrationTest "Start nodes before DR": sv3 never initialized; ordering topology 3 to 4 at 18:09:28.7, bound 18:09:37.796976 (suite start + 300 s); sv3 DsoRules_SetSynchronizerNodeConfig sent 18:09:42.180, refused SEQUENCING_TIME_NOT_ADMISSIBLE; suite aborted in teardown	11303 (AK)	test budget	dup of 11303 (11312 shape); not hard broken (4 of 34 jobs on 2026-11-10/11), #8432 not involved	evidence/11371-11372-rollforward-lsu-pre-dr-cutoff-dup-11303.md
11372	2026-11-11	43073759004	main 915616a77d (#8413)	130709572782 roll-forward-lsu (0)	3.7.3-snapshot.20261111.20904.0.vbe797d9d	RollForwardLsuDRIntegrationTest "Start nodes before DR": sv2Scan timeout, sv2/sv3/sv4 never initialized; 1 to 2 sequencers (sv1, sv3, strong quorum 2) at 18:20:27.66, sv3 BFT node state transfer only 18:21:00.7, epoch 40 stalled 18:20:29 to 18:21:08; sv2 add sv to Dso sent 18:20:30.85, sequenced 18:21:08.28 after bound 18:20:56.897578, refused SEQUENCING_TIME_NOT_ADMISSIBLE	11303 (AK)	test budget	dup of 11303 (new delay: joiner sequencer in ordering topology before its node is up); #8432 and Canton bump #8441 not involved	evidence/11371-11372-rollforward-lsu-pre-dr-cutoff-dup-11303.md
```

## known-families.md text (append to section AK)

```
- Dups 11371 (2026-11-11, 08774bc437, 11312 shape: 4th sequencer 9 s before the bound, sv3 SetSynchronizerNodeConfig
  4.4 s after it) and 11372 (2026-11-11, 915616a77d, Canton 3.7.3-snapshot.20261111, new delay: sv2/sv3/sv4 join in
  parallel and late; the sv3 sequencer is in the 2-node ordering topology (strong quorum 2) from 18:20:27.66 but its
  BFT node starts the onboarding state transfer only at 18:21:00.7, epoch 40 stalls 39 s across the bound, sv2
  `add sv to Dso` refused; the test reports sv2Scan, not an SV app). roll-forward-lsu (0): 4 of 34 jobs on the 32
  post-merge main runs of 2026-11-10/11, never two consecutive runs; #8432 and the Canton bump #8441 are not involved.
```
Index table row AK: parent/main refs `11303 (dups 11312, 11328, 11336, 11371, 11372)`.

## Lesson

Lesson: none (the fixed pre-DR budget is the AK lesson candidate already; these add no new pattern).

## Suggested action (user runs on the host)

Close both as duplicates of 11303 (inline one-line comment):
```
direnv exec . ci-triage/scripts/close_duplicates.py 11303 11371 11372 --comment 'Same pre-DR sequencing cutoff: a joining SV still had a join step to sequence when the pre-DR sequencers reached suite start plus 300 s, and every later send was refused with SEQUENCING_TIME_NOT_ADMISSIBLE.'
```
Recurrence comment on the parent: six hits since 2026-11-09, still unassigned.
```
gh issue comment 11303 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-11T210621Z-inbox/comments/11303.md
```
Assignee suggestion: rautenrieth-da. Evidence: the budget became too short with "Use DA BFT in all tests" (#8234,
b77153c658, Robert Autenrieth, the last change to the test file, `git log -5 915616a -- <test>` in the packet). That
is the cause recorded on 11303. If assigned:
```
gh issue edit 11303 -R DACH-NY/cn-test-failures --add-assignee rautenrieth-da
direnv exec . ci-triage/scripts/set_status.py Triaged 'In progress' 11303
```
(The board was not read here; set_status.py changes 11303 only if it is in Triaged.)

Priority: frequent flake, not hard broken. One failure in about 8 runs over two days on an integration shard that
runs on every main merge.
