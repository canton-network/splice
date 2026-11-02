# Host commands, 2026-10-29c (10903-10911)

```zsh
gh issue close 10903 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10901: Auth0 login 401 during the CI secret rotation, see #10896/#10901."
gh issue close 10905 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10901: Auth0 login 401 during the CI secret rotation, see #10896/#10901."
gh issue close 10909 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10901: compose Auth0 login access_denied during the CI secret rotation, see #10896/#10901."
gh issue close 10908 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896: GCS invalid_grant during the CI secret rotation, see #10896/#10901."
gh issue close 10904 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896: ParticipantKmsIdentitiesIntegrationTest hung until the job timeout on GCP KMS UNAUTHENTICATED during the CI secret rotation, see #10896/#10901."
gh issue close 10906 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896: ParticipantKmsIdentitiesIntegrationTest hung until the job timeout on GCP KMS UNAUTHENTICATED during the CI secret rotation, see #10896/#10901."
gh issue close 10910 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896: ParticipantKmsIdentitiesIntegrationTest hung until the job timeout on GCP KMS UNAUTHENTICATED during the CI secret rotation, see #10896/#10901."
gh issue close 10911 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10165: all 26 tests pass; checkErrors flags globalMediatorSv1 acknowledge-signed DEADLINE_EXCEEDED after 120 s to the sv1 sequencer during CopyVotesIntegrationTest (12:11:08). Not related to the secret rotation."
gh issue comment 10907 --repo DACH-NY/cn-test-failures --body "First hit (main 3568cba969, simtime (2)): SvTimeBasedOnboardingIntegrationTest, sv4 'JoiningNodeInitializer joining Dso with key failed' with 'IMPORT_ACS_ERROR(8,fdad8916): Cannot apply a repair command as the last event is a topology offset. Please reconnect to the synchronizer to move the ledger end' (10:56:06); the SV app exits, so the shard has no test report. The check came with the Canton 3.7 fork (canton/community/participant/.../repair/RepairServiceHelpers.scala:223, canton-network/splice#7944). Not related to the secret rotation."
gh issue edit 10907 --repo DACH-NY/cn-test-failures --title "SvTimeBasedOnboardingIntegrationTest: sv4 ACS import refused, last event is a topology offset (IMPORT_ACS_ERROR)"
```
