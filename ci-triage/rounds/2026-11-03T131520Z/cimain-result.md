# Group cimain (11152, 11153, 11154)

Packet: `evidence/11152-11153-11154-deploy-cimain-trigger-invoker-500.md`.

## Verdicts

| Ref | Attempt (from the log of the attempt) | Verdict | Parent |
|---|---|---|---|
| 11152 | 1, job 125829695539 | new occurrence in family J: cluster test invoker HTTP 500 09:20-09:40 UTC, rerun passed | - (parent of the group) |
| 11153 | 2, job 125831853150 | same | 11152 |
| 11154 | 3, job 125838729554 | same | 11152 |

Mapping issue to attempt is confirmed, not inferred: each attempt's failure notification step logs the issue URL.

## INDEX.tsv rows

```
11152	2026-11-03	41610142697	main 8fd9921238	125829695539 trigger_cimain / trigger_cluster_test (attempt 1)		Deploy CIMain trigger step: cluster test invoker answered HTTP 500 (Werkzeug default body) to all 4 curl tries 09:20:12-09:20:43; attempts 2 and 3 same until 09:40:29; attempt 4 at 09:44:12 triggered cni run 41613435215	new (J)		Infra one-off, invoker cause not verified (GCP logs). Comment, close 11153 11154 as dups, Infrequent / no repro.	evidence/11152-11153-11154-deploy-cimain-trigger-invoker-500.md
11153	2026-11-03	41610142697	main 8fd9921238	125831853150 trigger_cimain / trigger_cluster_test (attempt 2)		Same as 11152, attempt 2: invoker HTTP 500 09:24:56-09:25:28	11152 (J)		Close as dup of 11152.	evidence/11152-11153-11154-deploy-cimain-trigger-invoker-500.md
11154	2026-11-03	41610142697	main 8fd9921238	125838729554 trigger_cimain / trigger_cluster_test (attempt 3)		Same as 11152, attempt 3: invoker HTTP 500 09:39:57-09:40:29	11152 (J)		Close as dup of 11152.	evidence/11152-11153-11154-deploy-cimain-trigger-invoker-500.md
```

## known-families.md

Occurrence note for section J (append after the deploy_cimain bullet); index row of J unchanged:

```
- Cluster test trigger (splice workflow `cluster_tests.yml`, step "Trigger cluster test", used by Deploy CIMain and
  pr_cluster_test): `curl: (22) The requested URL returned error: 500` four times, body "500 Internal Server Error:
  The server encountered an internal error" (Werkzeug default, so the invoker app raised). 11152 11153 11154 = run
  41610142697 attempts 1-3, 2026-11-03 09:20-09:40 UTC, attempt 4 at 09:44 passed. curl already retries 500 for 60 s;
  cause is in the invoker (CLUSTER_TEST_INVOKER_URL, GCP da-cn-ci logs), not verified. One-off; each manual rerun
  files a new issue, so expect one issue per failed attempt.
```

## Issue commands

```
gh issue comment 11152 -R DACH-NY/cn-test-failures --body 'Infra one-off. Attempts 1-3 of Deploy CIMain run 41610142697 (this issue = attempt 1; 11153 = attempt 2; 11154 = attempt 3, per the failure notification step of each attempt) failed in Trigger cluster test: the cluster test invoker (CLUSTER_TEST_INVOKER_URL) answered HTTP 500 with the default Werkzeug page to all 4 curl tries, from 09:20:12 to 09:40:29 UTC. Attempt 4 at 09:44:12 triggered canton-network-internal run 41613435215; all later Deploy CIMain runs passed. curl already retries 500 within 60 s; the cause is in the invoker (GCP da-cn-ci logs for that window), not checked. Packet: ci-triage/evidence/11152-11153-11154-deploy-cimain-trigger-invoker-500.md on the triage branch.'
direnv exec . ci-triage/scripts/close_duplicates.py 11152 11153 11154 --comment 'Duplicate of #11152: same Deploy CIMain run 41610142697, a later attempt; the cluster test invoker answered HTTP 500 from 09:20 to 09:40 UTC, attempt 4 passed.'
gh issue edit 11152 -R DACH-NY/cn-test-failures --title 'Deploy CIMain: cluster test invoker returned HTTP 500 for 20 min on 2026-11-03 (3 attempts), rerun passed'
direnv exec . ci-triage/scripts/set_status.py Inbox 'Infrequent / no repro' 11152
```

## Open

- Why the invoker raised: needs its logs (GCP project da-cn-ci, 2026-11-03 09:20-09:41 UTC). Only if it recurs.
