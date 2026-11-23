# 11566 - docs (and all 70 failed jobs of two main runs): apps-sv Test does not compile, `SnapshotState.Ready(_)` after #7796 made it 2-ary (run 44327510557)

New, parent of the burst. Post-merge main run at a4ca636ec0 (#8558, "Export sequencer onboarding states on separate
queue"): the docs job fails in `apps-sv / Test / compileIncremental` with 14 errors, the first being
`SvOnboardingSnapshotServiceTest.scala:361:42: wrong number of arguments for pattern ... SnapshotState.Ready(file:
java.nio.file.Path, sha256Base64: String)`; the other 13 are `-Wunused` follow-ons of the same failed typecheck. Not a
flake: a semantic merge race. #7796 (merged 12:09Z) changed `SnapshotState.Ready` to two fields; #8558 was branched
before #7796 and added `case Some(SnapshotState.Ready(_))` to the test; it merged at 14:44Z without being rebuilt on top
of #7796. Every failed job of runs 44327510557 (a4ca636ec0) and 44328806873 (10de6749e0) carries the same error line
(35 + 35 jobs). Fixed on main by splice#8569 (53e7efb8e2, merged 17:58:38Z, `Ready(_)` -> `Ready(_, _)`); the
post-merge run on that commit (44364590139) has docs and static_tests green. Nothing to fix here.

- Run: https://github.com/canton-network/splice/actions/runs/44327510557, main a4ca636ec0 ("Export sequencer
  onboarding states on separate queue (#8558)"), job 134882680182 `ci / docs / docs`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (not involved: the job fails at compile time).
- Component: splice test code (apps-sv), merge race between #7796 and #8558.

## 1. Failing step: apps-sv Test compile

The docs job log ends in a Scala compile failure of `apps-sv` test sources.

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134882680182/logs > log/11566/job.log
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11566/job.log | grep -a -E '^\[error\]' | grep -a -E 'scala:[0-9]+|errors found|Compilation failed' | cut -c1-260
```
```
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:361:42: wrong number of arguments for pattern org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.SnapshotState.Ready(file: java.nio.file.Path, sha256Base64: String)
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:43:15: private val sha256Base64 in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:48:15: private method key in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:55:15: private method sequencerKey in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:60:15: private method writeSnapshot in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:65:15: private method preventDeletion in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:71:15: private method unavailable in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:80:15: private method withService in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:81:7: private default argument in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:82:7: private default argument in class SvOnboardingSnapshotServiceTest is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:311:26: parameter snapshotKey in class Export is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:662:74: parameter service in anonymous function is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:662:83: parameter clock in anonymous function is never used
[error] /__w/splice/splice/apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala:698:74: parameter service in anonymous function is never used
[error] 14 errors found
[error] (apps-sv / Test / compileIncremental) Compilation failed
```

## 2. Mechanism: semantic merge race between #7796 and #8558

#7796 gave `SnapshotState.Ready` a second field; #8558 added a one-field pattern on it in the test.

```
M=apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotService.scala
F=apps/sv/src/test/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala
git show ec059037fa -- $M | grep -E '^[-+].*Ready'
git show a4ca636ec0 -- $F | grep -E '^[-+].*Ready'
```
```
-              case Snapshot(_, Stored.Finished(_, file)) => SnapshotState.Ready(file)
+                SnapshotState.Ready(file, sha256Base64)
-    final case class Ready(file: Path) extends SnapshotState
+    final case class Ready(file: Path, sha256Base64: String) extends SnapshotState
+            case Some(SnapshotState.Ready(_)) =>
```

The PR head of #8558 did not contain #7796; main history puts #7796 two commits before #8558.

```
gh pr view 8558 -R canton-network/splice --json createdAt,mergedAt,headRefOid | jq -c .
gh pr view 7796 -R canton-network/splice --json mergedAt | jq -r .mergedAt
git fetch -q origin 86631f9ed5466315c9a6a9823c4a2b2fb1ec73bc
git merge-base --is-ancestor ec059037fa 86631f9ed5 && echo "#7796 in #8558 head" || echo "#7796 NOT in #8558 head"
git log --format='%h %p %s' -3 a4ca636ec0
```
```
{"createdAt":"2026-11-17T22:31:02Z","mergedAt":"2026-11-18T14:44:03Z","headRefOid":"86631f9ed5466315c9a6a9823c4a2b2fb1ec73bc"}
2026-11-18T12:09:00Z
#7796 NOT in #8558 head
a4ca636ec0 465b29cc47 Export sequencer onboarding states on separate queue (#8558)
465b29cc47 ec059037fa AGENT.md: refer to new DAML_STYLE.md and add instructions for TODO notes (#8551)
ec059037fa 8399d63908 Resume interrupted onboarding snapshot downloads (#7796)
```

## 3. Fix already on main: splice#8569

```
gh pr view 8569 -R canton-network/splice --json number,title,mergedAt | jq -c .
git show 53e7efb8e2 --stat --format='%h %ad %s' --date=iso
git show 53e7efb8e2 | grep -E '^[-+]' | grep -vE '^(\+\+\+|---)'
```
```
{"mergedAt":"2026-11-18T17:58:38Z","number":8569,"title":"Fix SvOnboardingSnapshotServiceTest compilation"}
53e7efb8e2 2026-11-18 18:58:37 +0100 Fix SvOnboardingSnapshotServiceTest compilation (#8569)

 .../splice/sv/onboarding/sponsor/SvOnboardingSnapshotServiceTest.scala  | 2 +-
 1 file changed, 1 insertion(+), 1 deletion(-)
-            case Some(SnapshotState.Ready(_)) =>
+            case Some(SnapshotState.Ready(_, _)) =>
```

## 4. Affected runs: the two post-merge runs between #8558 and #8569

Post-merge runs on main since 14:00Z; only two `CI post-merge` runs carry a4ca636ec0 without 53e7efb8e2.
The docs and static_tests jobs pass on the commit before (465b29cc47) and on the fix commit.

```
gh api "repos/canton-network/splice/actions/runs?branch=main&created=2026-11-18T14:00:00Z..2026-11-19T06:00:00Z&per_page=100" --jq '.workflow_runs[] | select(.name=="CI post-merge to main or release-line branches") | "\(.id) \(.head_sha[0:10]) \(.created_at) \(.event) \(.conclusion)"'
for r in 44322981802 44328806873 44364590139; do gh run view $r -R canton-network/splice --json jobs | jq -r --arg r $r '.jobs[] | select(.name|test("docs / docs|static_tests / static_tests")) | "\($r) \(.name) \(.conclusion)"'; done
```
```
44364590139 53e7efb8e2 2026-11-18T17:58:41Z push null
44328806873 10de6749e0 2026-11-18T14:50:59Z push failure
44327510557 a4ca636ec0 2026-11-18T14:44:06Z push failure
44322981802 465b29cc47 2026-11-18T14:19:25Z push failure
44322981802 ci / docs / docs success
44322981802 ci / static_tests / static_tests success
44328806873 ci / static_tests / static_tests failure
44328806873 ci / docs / docs failure
44364590139 ci / static_tests / static_tests success
44364590139 ci / docs / docs success
```

(44322981802 failed for other reasons, e.g. 11597 docker-compose (1), family BG; its docs job is green.)

## 5. Confirming grep across all failed jobs of both runs

Every failed job of both runs (35 each) has the compile error line exactly once.

```
for r in 44327510557 44328806873; do gh run view $r -R canton-network/splice --json jobs | jq -r --arg r $r '.jobs[] | select(.conclusion=="failure") | "\($r) \(.databaseId) \(.name)"'; done > log/11566/sib/jobs.txt
cut -d' ' -f2 log/11566/sib/jobs.txt | xargs -P8 -I{} sh -c 'gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/{}/logs 2>/dev/null | grep -a -c "SvOnboardingSnapshotServiceTest.scala:361:42: wrong number of arguments for pattern" > log/11566/sib/{}.n'
while read r j name; do echo "$r $(cat log/11566/sib/$j.n)"; done < log/11566/sib/jobs.txt | awk '{n[$1" matches="$2]++} END{for(k in n) print k, "jobs="n[k]}' | sort
```
```
44327510557 matches=1 jobs=35
44328806873 matches=1 jobs=35
```

Issues filed for these jobs (tracker search `created:2026-11-18T14:40:00Z..15:30:00Z` plus the later page): 11566 to
11596 and 11598 to 11636 except 11597 (run 44322981802) and 11637 (run 44328860614, not a main post-merge run, not
checked). 11566 is the earliest of the burst.

## Verdict

New, not a duplicate: 11566 is the first issue filed for this break, and no open tracker issue mentions
`SvOnboardingSnapshotServiceTest`. Real failure (hard broken main from a4ca636ec0 to 53e7efb8e2, 14:44Z to 17:58Z),
not a flake. Cause: merge race between splice#7796 and splice#8558 (PR not rebuilt on the new main before merge).
Fix location: splice test code; already fixed by splice#8569 (53e7efb8e2), and the post-merge run on it has docs and
static_tests green. No fix branch. Not verified here: the integration test shards of run 44364590139 (still in
progress at triage time); 11637 not checked. Suggested action (not run, the user decides): close 11566 as completed
with a pointer to splice#8569, and close the sibling issues of runs 44327510557 and 44328806873 as duplicates of 11566
after the one-line grep of section 5 (`close_duplicates.py 11566 <refs>`).
