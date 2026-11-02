# 2026-10-29b inbox: test credentials broke on 10-29 ~09:40 (10893-10902)

Runs (canton-network/splice post-merge, 10-29 09:41-09:50): 40851563347 (release-line-0.9.x 78bddc544a),
40852038335 (release-line-0.9.3 07aaf50b54), 40852237254 (release-line-0.9.4 849cfb1f1b), 40852799278 (main
3568cba969, still running at triage time). Not the main test-compile break (all shas after #8109).

## A. GCS service-account key rejected (10894 10895 10896 10897 10898 10899 10900)

`GcpBucketPeriodicBackupIntegrationTest` ("produce backup dumps in the background") and
`PeriodicTopologySnapshotIntegrationTest` ("produces a topology snapshot in the background") fail on every branch.
Artifact logs-key-download-0 of run 40852799278, canton_network_test.clog, PeriodicParticipantIdentitiesBackupTrigger:

```
com.google.cloud.storage.StorageException: Error getting access token for service account: 400 Bad Request
POST https://oauth2.googleapis.com/token
{"error":"invalid_grant","error_description":"Invalid JWT Signature."}, iss: splice-data-export-tests@da-cn-splice.iam.gserviceaccount.com
	at org.lfdecentralizedtrust.splice.util.GcpBucket.dumpBytesToBucket(GcpBucket.scala:39)
```

"Invalid JWT Signature" = the CI's key for splice-data-export-tests@da-cn-splice was deleted/disabled or the GHA
secret holds a stale key. Post-merge runs at 08:28/08:58 passed, failures start with jobs from 09:42, so the key
changed between ~09:00 and 09:40 on 10-29. Fix: new key in the GHA secret (CI infra). Umbrella: 10896.

## B. Auth0 CLI client-credentials login rejected (10901 10902)

DockerComposeValidatorFrontendIntegrationTest "docker-compose based validator works" on release-line-0.9.4 and
0.9.3: `Failed to start docker-compose validator (with auth)`; compose.log (artifact logs-docker-compose-1 of run
40852237254):

```
 ▸    Failed to start secret machine login: failed to fetch access token using client credentials.
error: oauth2: "access_denied" "Unauthorized".
```

from `build-tools/splice-compose.sh:57` `auth0 login --domain ${AUTH0_TENANT} --client-id
"$AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID" --client-secret "$AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET"`. The same job
passed on release-line-0.9.x at 09:45-10:15, so not proven to be a global secret change; same time window as A,
so likely the same credential rotation. Umbrella: 10901. Needs the CI secrets owner.

## C. 10893 SplitwellFrontendIntegrationTest (release-line-0.9.x, frontend-wall-clock-time (2))

"settle debts with a single party", step `error-on-unknown-party` of bobSplitwell: expected exactly one error
element, found none (SplitwellFrontendIntegrationTest.scala:291). First hit, no cause established; keep open.
