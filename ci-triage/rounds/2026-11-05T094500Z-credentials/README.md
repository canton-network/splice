# Round 2026-11-05T094500Z-credentials

Source: live issue list (gh api, open issues since 2026-11-04), no board export; the user reports 51 Inbox items.
User: secrets were rotated, Pulumi secret config changed (splice#8246); report secret problems first; clean inbox.

## CI credentials (parent 11217)

Runs 41993608457 (0.9.x), 41993637533 (0.10.0), 41993663804 (0.9.4), 41993685927 (0.9.3), 41995289384 (main), pushed
08:19-08:32. Auth0 client credentials, GCP data export SA key and GCP KMS credentials (all `_V2`, read since
splice#8248, merged 11-04 19:04) rejected from about 08:55 UTC. First errors: KMS 08:55:34, GCP 08:58:12, Auth0
09:02:23. Main runs 19:05 to 00:16 passed docker-compose (1), frontend (1)/(2), key-download (0) with `_V2`.
Job logs and two artifacts in `log/inbox-2026-11-05/` (sandbox).

| Ref | Run | Job | Evidence in its job log |
|---|---|---|---|
| 11217 (parent) | main | docker-compose (1) | auth0 access_denied |
| 11198 11199 11201 11215 | 0.10.0, 0.9.x, 0.9.4, 0.9.3 | docker-compose (1) | auth0 access_denied |
| 11213 11216 | main | frontend-wall-clock-time (2), (1) | auth0 401 |
| 11197 11214 | 0.10.0, main | key-download (0) | Invalid JWT Signature (backup dumps) |
| 11200 11202 11205 11206 11210 | 0.9.4, 0.10.0, 0.9.x, 0.9.3, main | wall-clock-time (1)/(0) | Invalid JWT Signature (topology snapshot) |
| 11203 11204 11209 | 0.9.4, 0.9.x, 0.9.3 | wall-clock-time (9) | Invalid JWT Signature (backup dumps) |
| 11218 | main | wall-clock-time (9) | interrupted in ParticipantKmsIdentitiesIntegrationTest; artifact: KMS UNAUTHENTICATED 08:55:34 |

Not credentials, same runs, still to triage: 11207 (0.9.4 wall-clock (8), ValidatorReonboardingWithPartiesToMigrate
CommandFailure), 11208 (0.9.x wall-clock (3), sbt 40 min timeout), 11211 (0.9.4 wall-clock (0), checkErrors: BFT
orderer can't connect to database 08:55:08), 11212 (0.9.4 wall-clock (4), sbt 40 min timeout).

Possibly credential-related, logs needed: 11194 (CircleCI monitor_sv_catchup_test on cni #7949), 11179
(auth0_user_cleanup), 11196 (cluster_lock_reaper).

## Outcome

User posted a shortened 11217 comment (`comments/11217-posted.md`; style rules now in issue-comment.md) and judges it
bad timing: the credentials were rotated while the 08:19-08:32 runs were testing. Supported by main 6ecd22cf (09:28):
frontend-wall-clock-time (2) passed. To confirm: docker-compose (1) and key-download (0) of 6ecd22cf / 5bfb8eed pass,
then 11217 can be closed.

## Inbox to zero (2026-11-05, from 10:15)

User: get the inbox to 0; board export requested (`export_board.py`). Until it lands, the queue is the live issue list.
Groups triaged in background forks; state: open, presented, handed out, ran, skipped.

| Group | Refs | Signature | State |
|---|---|---|---|
| resint | 11189 11191 | main resource-intensive (0), 11-04 14:27 and 17:52 | ran 2026-11-05: dups of 11160 (user dropped "family L" from the comment); user linked 11160 to splice#8234 (DA BFT everywhere, simtime parallelism 4 to 6) |
| wc7 | 11193 11195 | main wall-clock-time (7), 11-04 21:43 and 22:40 | open |
| fe | 11186 11187 11192 | main frontend-wall-clock-time (2), (0), (1) on 11-04; 11192 Splitwell settle debts | open |
| burst | 11207 11208 11211 11212 | release-line runs 08:19-08:32, not credentials (CommandFailure, 2x sbt 40 min timeout, BFT orderer DB) | ran 2026-11-05: 11208 11212 dup 10358; 11207 (backport #7909), 11211 (ignore pattern) Triaged |
| singles | 11188 11182 | main app-upgrade (0) 11-04 14:11; docker-canton-simtime (0) 11-04 | open |
| needs-log | 11194 11196 11179 | CircleCI monitor_sv_catchup_test; cni cluster_lock_reaper, auth0_user_cleanup | log requested |

User: duplicate one-liners inline (no file); commit body files before handing out commands (both now in the skills).
Board export 2026-11-05 11:02: 30 Inbox, 19 Triaged, 7 In progress. Group `old`: 14 items from earlier rounds that
only need their Status (and 4 a comment). Fork `old3`: 11164, 11172, 11175.

User: present one item at a time, most important first, prefer items with many duplicates. No family letters in issue comments.
- 11182 (scan false ERROR after BFT success): ran 2026-11-05, Triaged, assigned isegall-da.
- 11195 (scheduled vetting vs shared alice participant): ran 2026-11-05, Triaged, assigned julientinguely-da. User corrected "latest DARs" (Splitwell vets all versions 0.1.19-0.1.25); the fork had reconstructed a log line, fixed; rule: verify agent quotes.
