# 2026-10-29 inbox: 144 issues = one main test-compile break, fixed by #8109

Runs (all canton-network/splice, main): post-merge 40749783712 (8a73a6d085), 40755669766 (e63dc824dd),
40775321053 (dd1bb361df), nightly Wall Clock PG14 40820911167, nightly mainnet-Daml 40821274875, daily store
performance 40813530314 (all dd1bb361df). Every job fails at test compile:

```
[error] .../GovernanceLockTimeBasedIntegrationTest.scala:715:30: not found: value truncateInstant
```

Cause: merge race. #8097 (Support fractional seconds in ISO 8601 parser) removed `GovernanceLockTest.truncateInstant`,
#8056 (lock transfers in the Scan registry) added new uses; each PR was green on its own base. Fixed by #8109
"[ci] unbreak main" (Itai); post-merge green again from 5e160be88c (10-29 08:28).

Verified by sampling 4 failing jobs per run (1 for the perf run): all show the truncateInstant error.
Consequence: the 10-29 mainnet-Daml nightly did not exercise #8065 (PV 36 founding fix); next chance 10-30 05:00.
Host commands: host-commands-main-break.sh (one close per issue).
