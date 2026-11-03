# 10736: ciperiodic deploy_upgrade (CircleCI pipeline 87425, job 525815), dup of 10677, NOT family N

Verdict: same as 10677 (needs log). Correction to `evidence/10699-10701-ciperiodic-pv36.md`, which listed 10677
as "likely the same" as the PV 36 SV1 bootstrap (family N): deploy_upgrade is a different failure.

- Trigger `.circleci/triggers/enabled/ciperiodic-upgrade.json`: "Tests an upgrade from the latest release to Splice
  main", Mon-Fri 10:00 and 18:00. The base deploy runs the release-line splice set as the submodule of
  build/ciperiodic-upgrade (10736: 3514d7e8ad, release-line-0.9.x = #8064, Canton 3.6.4; 10677: 3eb742f434), so the
  SV1 bootstrap runs 0.9.x code, which does not have the 10520 bug, and the upgrade to main does not bootstrap a DSO.
- deploy_upgrade issues: only 10677 (10-27 10:00 run) and 10736 (10-28 10:00 run). The runs from 10-23 18:00 to 10-26
  18:00, inside the family N window, filed nothing.
- Both branch commits add the same config, following cni #7703 "Switch upgrade to active and active version to
  legacy" (10-27 07:14):

```
ghx "/repos/DACH-NY/canton-network-internal/commits/9aa5be2e39"   # build/ciperiodic-upgrade, 10-28 11:23
```
```
cluster/deployment/ciperiodic/config.yaml
 synchronizerMigration:
   frozenMigrationId: 0
+  active:
+    id: 0
+    version: local
+    sequencer:
+      enableBftSequencer: true
```

  The first failure is the first run after #7703. First suspect stays the `synchronizerMigration.active` switch in
  the upgrade flow (owner of #7703: Julien). The #8007 backport in the base (dependency bumps only) is not the cause:
  10677 failed before it existed.

Paste: the failing step and its last ~40 lines of
https://app.circleci.com/pipelines/github/DACH-NY/canton-network-internal/87425/workflows/e6e4c9ac-14c9-4d59-9ce5-b49679b26c78/jobs/525815
(whether it fails in the base deploy or in the upgrade step after the switch).
