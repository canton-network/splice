CI credentials broken, every run fails: since about 08:55 UTC on 2026-11-05, Auth0, GCP data export and GCP KMS reject the credentials the scala_test jobs get from the `_V2` GitHub secrets.

Failing jobs: docker-compose, the Auth0 frontend login tests, and the GCP bucket and KMS tests in key-download and wall-clock-time.

Three different credentials are rejected:

```
09:04:53 Failed to start secret machine login: failed to fetch access token using client credentials.
         error: oauth2: "access_denied" "Unauthorized".
09:02:23 com.auth0.exception.APIException: Request failed with status code 401: Unauthorized
08:58:12 StorageException: Error getting access token for service account: 400 Bad Request
         {"error":"invalid_grant","error_description":"Invalid JWT Signature."}, iss: splice-data-export-tests@da-cn-splice.iam.gserviceaccount.com
08:55:34 KMS operation `signing with key KmsKeyId(b14aeff8-...)` failed: ... UNAUTHENTICATED: Request had invalid authentication credentials.
```

The secrets are set (the `Check required secrets are set` step added by canton-network/splice#8248 passed), so the values, not the names, are wrong. The same `_V2` secrets worked until this morning. All three credentials broke within a few minutes of each other.
