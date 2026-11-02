# Host commands, 2026-10-29b (10893-10902)

```zsh
gh issue comment 10896 --repo DACH-NY/cn-test-failures --body "Umbrella for the GCS test-credential failure since 10-29 ~09:40 (all branches): GcpBucketPeriodicBackupIntegrationTest and PeriodicTopologySnapshotIntegrationTest fail with 'com.google.cloud.storage.StorageException: Error getting access token for service account: 400 Bad Request ... {\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}, iss: splice-data-export-tests@da-cn-splice.iam.gserviceaccount.com' (GcpBucket.dumpBytesToBucket). The service-account key used by CI was deleted/disabled or the GHA secret is stale; runs at 08:28/08:58 still passed. Needs a new key in the CI secret."
gh issue close 10894 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896"
gh issue close 10895 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896"
gh issue close 10897 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896"
gh issue close 10898 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896"
gh issue close 10899 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896"
gh issue close 10900 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10896"
gh issue comment 10901 --repo DACH-NY/cn-test-failures --body "Umbrella: DockerComposeValidatorFrontendIntegrationTest fails to start the compose validator with auth on release-line-0.9.4 (and 0.9.3, #10902): 'Failed to start secret machine login: failed to fetch access token using client credentials ... error: oauth2: \"access_denied\" \"Unauthorized\".' from build-tools/splice-compose.sh:57 (auth0 login with AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID/SECRET). Same window as the GCS key failure #10896; release-line-0.9.x passed the same job at 09:45-10:15. Needs the CI secrets owner to check the Auth0 test M2M client."
gh issue close 10902 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10901"
gh issue comment 10893 --repo DACH-NY/cn-test-failures --body "First hit (release-line-0.9.x 78bddc544a): SplitwellFrontendIntegrationTest 'settle debts with a single party', step error-on-unknown-party of bobSplitwell found no error element (SplitwellFrontendIntegrationTest.scala:291). Cause not established; keep open for recurrence."
```
