# 2026-10-29c: 10903-10911

| Ref | Run / job | Verdict |
|---|---|---|
| 10903 | 40852799278 (main 3568cba969) frontend-wall-clock-time (1), job 123376759293 | Auth0 rotation: `allow login via auth0` -> `APIException: Request failed with status code 401: Unauthorized` (Auth0Util.requestManagementAPIToken:85), 10:45. Dup #10901 |
| 10905 | same run, frontend-wall-clock-time (2), job 123376759337 | Auth0 rotation: `redirect to the previous page after login`, `allow login via auth0 and persist user name` (10:39). Dup #10901 |
| 10909 | same run, docker-compose (1), job 123377426498 | Auth0 rotation (access_denied in compose auth login). Dup #10901 |
| 10908 | same run, wall-clock-time (2), job 123376704309 | GCS rotation: `produces a topology snapshot in the background`, 12x invalid_grant. Dup #10896 |
| 10904 | 40852038335 (0.9.3) wall-clock-time (9), job 123374573293 | GCP rotation, KMS variant: ParticipantKmsIdentitiesIntegrationTest hangs from 10:08 until the 60 min job timeout (SIGINT 10:48); canton-standalone-kms-identities-validator.clog: GcpKms `UNAUTHENTICATED`, REGISTER_KMS_KEY_INTERNAL_ERROR (10:09:04). Dup #10896 |
| 10906 | 40852237254 (0.9.4) wall-clock-time (9), job 123374821015 | Same KMS hang (SIGINT 10:52). Dup #10896 |
| 10910 | 40852799278 (main) wall-clock-time (8), job 123376704257 | Same KMS hang (ParticipantKmsIdentitiesIntegrationTest from 10:42, SIGINT 11:26). Dup #10896 |
| 10907 | 40852799278 (main) simtime (2), job 123376773296 | NEW family U: SvTimeBasedOnboardingIntegrationTest, sv4 `JoiningNodeInitializer joining Dso with key failed`: `IMPORT_ACS_ERROR ... Cannot apply a repair command as the last event is a topology offset` (10:56:06); SV app exits, no test report. Canton 3.7 check (RepairServiceHelpers.scala:223, #7944). Not the rotation. Open, first hit |
| 10911 | 40868731583 (release-line-0.9.x 4df23ca850, 12:27, after the rotation) wall-clock-time (5), job 123430548676 | Not credentials: 26/26 tests pass, checkErrors flags mediator globalMediatorSv1 `acknowledge-signed` DEADLINE_EXCEEDED after 120 s to sequencer :5108 during CopyVotesIntegrationTest (12:11:08) = family B signature (1), umbrella #10165 (sequencer blacklist lines not checked) |

Rotation conclusion: every credential failure is inside the 09:40-11:27 UTC window; the first run after it (10911,
12:27) has no credential error, so the rotation looks complete. No GCP/Auth0 failure after ~11:30 seen.
Artifact used for the KMS evidence: run 40852038335 logs-wall-clock-time-9 (host log/t1029c-art/wct9).
