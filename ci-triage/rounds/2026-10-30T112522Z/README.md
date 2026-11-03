# 2026-10-30b: main compile break after #7026 (98 issues)

Issues 10939 to 11036, all from three post-merge runs on main:

| Run | Commit | Failed jobs |
|---|---|---|
| 41042191156 | 7a0e55d896 #7026 enable configuring auth in participant admin API | 33 |
| 41044455724 | e4798d2c54 #8030 bump oidc-client-ts | 33 |
| 41044776115 | aef6cd9ea5 #8146 fix update history backfilling alerts | 32 |

Every failed job's first error (98 of 98, `log/inbox-10-30b/first-errors.tsv`) is the test compile of
`Sv1BootstrapProtocolVersion36IntegrationTest`:

```
[error] .../Sv1BootstrapProtocolVersion36IntegrationTest.scala:48:69: value port is not a member of org.lfdecentralizedtrust.splice.config.ClientConfigWithAuth
[error] .../Sv1BootstrapProtocolVersion36IntegrationTest.scala:47:7: not enough arguments for constructor ParticipantAdminConnection ... Unspecified value parameter tokenProvider.
```

Cause: semantic merge conflict. #7026 (merged 10-30 10:38) made the participant admin API client config
`ClientConfigWithAuth` and added a required `tokenProvider` to `ParticipantAdminConnection`; #8125 (merged 10-29) added
the test with the old constructor. Fix: canton-network/splice#8150 (auto-merge), updates the test like #7026 updated
`ManualStartIntegrationTest`.

Verdict: 10939 parent, stays open until #8150 merges; 10940 to 11036 duplicates of 10939.
Host commands: `host-commands.md`.
