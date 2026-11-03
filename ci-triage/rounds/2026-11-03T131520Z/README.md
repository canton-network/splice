# Round 2026-11-03T131520Z

Data source: host exports `inbox.jsonl` (2026-11-03 13:14, 35 items) and `issues-since-2026-10-20.jsonl`
(13:11, 721 issues). Access: splice via `gh api`; no `ghx`, no DACH-NY token. Job logs of the splice runs in
`log/<ref>/job.log` (sandbox only).

The commands of round 2026-11-02T153358Z-singles did not run: 10891, 11138, 11139 still open, 11140 has no
comment. They are handed out again in this round.

## Classification

| Ref | Class | Note |
|---|---|---|
| 10495, 10496, 11104, 11105, 11128 | done | previous verdicts (STATE.md); propose Triaged |
| 10891, 11138, 11139, 11140 | done | round 2026-11-02T153358Z-singles, commands not run |
| 10728, 10893, 10917, 10918, 10919 | waiting | cause open on purpose (STATE.md) |
| 10479, 10680, 11106, 11117, 11137 | needs-log | as in STATE.md |
| 11142, 11151 | needs-log | CircleCI deploy_sv_reonboard (pipelines 87882 job 528092, 87945 job 528370), like 11106, 11137 |
| 11145 | needs-log | cni GHA 41497863226 backup_status_cluster (Devnet Backup Status), like 11117 |
| 11146 | out of scope | blackduck vulnerability (pypi:filelock), filed with Status Inbox |
| 11152, 11153, 11154 | new | group cimain: run 41610142697 attempts 1-3, trigger_cimain / trigger_cluster_test, step Trigger cluster test |
| 11158 | new | group scanmon: ScanPartyMonitorScriptIntegrationTest, suspected Z (11128) |
| 11143 | new | group walletmetrics: WalletMetricsTest, suspected dup of 10732 |
| 11144 | new | group traffic: TrafficBasedRewardsDryRunTimeBasedIntegrationTest CIP-104 |
| 11160 | new | group tscli: release-line-0.10.0 TokenStandardCliTestDataTimeBasedIntegrationTest expire amulet allocations |
| 11161 | new | group splitwell: SplitwellFrontendIntegrationTest settle debts with a single party (main) |
| 11141, 11147, 11155 | new | group checkerrors: tests pass, checkErrors (createDatabase connect; 1 line; DbLockedConnection lost) |
| 11163 | new | group simtime1: simtime (1) Run Tests failed without a test summary |
