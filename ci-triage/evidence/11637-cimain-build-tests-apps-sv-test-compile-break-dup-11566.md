# 11637 - Deploy cimain build_tests: apps-sv Test sources do not compile, SnapshotState.Ready arity

DUPLICATE of 11566 (family BH), confirmed by the family's confirming grep (1 match). Deploy cimain run, job Build
tests, built splice 10de6749e0 (#7932), the same splice commit as the run already covered by 11566. No tests run:
sbt stops after 494 s (2026-11-18 16:16 UTC) because the Test sources of apps-sv do not compile, with 14 errors.
The first is at `SvOnboardingSnapshotServiceTest.scala:361:42`, a pattern with the wrong number of arguments for
`SnapshotState.Ready(file: java.nio.file.Path, sha256Base64: String)`; the other 13 are -Wunused follow-ons in the
same file (lines 43, 48-82, 311, 662, 698). Merge race: splice#7796 made `Ready(file, sha256Base64)`, splice#8558
(branched before it) added `case Some(SnapshotState.Ready(_))`. Hard broken splice main 14:44Z-17:58Z, not a flake;
fixed by splice#8569 (53e7efb8e2), and the next cimain run built 53e7efb8e2 with Build tests green.

- Splice built: 10de6749e0 (#7932).
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 10de6749e0); not involved.
- Component: splice test code (merge race), hard broken main.
- Parent: 11566, closed as completed (not as a duplicate).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Fix is on main and the built commit predates it

```
git merge-base --is-ancestor 53e7efb8e2 origin/main && echo fix-on-main
git merge-base --is-ancestor 10de6749e0 53e7efb8e2 && echo run-sha-before-fix
```
```
fix-on-main
run-sha-before-fix
```

The next Deploy cimain run (2026-11-18 17:58 UTC) built splice 53e7efb8e2 and its Build tests job succeeded.

## Verdict

Duplicate of 11566 (family BH), the parent the other 69 BH issues point to; 11566 is closed as completed, not as a
duplicate. Real break from a splice merge race, not a flake; already fixed on main by splice#8569 (53e7efb8e2), and
cimain picked the fix up in its next run. Suggested action (not run): close 11637 as duplicate of 11566. Nothing
fixed here. Not verified: an earlier failed cimain run (10:50Z) predates the race and is unrelated to this ref.
