# 2026-11-02: inbox triage

## Infra (fork C): `infra-c.md`, `host-commands-c.md`

| Issue | Job | Verdict |
|---|---|---|
| 11117 | cni Devnet Backup Status 41175569098, 10-31 06:00 | first night after the Devnet LSU switch to serial 7 (cni #7836/#7837); stale Cloud SQL backup at 06:00, recovered by 11-01; stays open, needs-log, Krzysztof |
| 11118 | cni Devnet Backup 41166827191, 10-31 04:00 | dup of 11117 (same night, failed ~07:10) |
| 11106 | CircleCI 87744 deploy_sv_reonboard, 10-30 16:00 | not 10844 (deployed splice contains #8125); intermittent, needs-log job 527403 |
| 11137 | CircleCI 87814 deploy_sv_reonboard, 11-02 08:00 | same as 11106 until the logs show otherwise; needs-log job 527732 |

## mainnet-Daml nightlies 10-31, 11-01, 11-02 (fork A): `nightly-a.md`, `host-commands-a.md`

Runs 41171022068, 41275427340, 41391921766, all on main 3525c637db (Canton 3.7.1, X1 #8149 and X3 #8154 in). No new cause.

| Issues | Verdict |
|---|---|
| 11108 11109 11110 11111 11113 11115 11120 11121 11122 11123 11126 11127 11129 11132 11133 11135 | dup of 10927 (X2 outbox flusher, log check only; 11132 is the LSU successor `::37-2` variant) |
| 11114 11116 11124 11125 11130 11131 | dup of 10933 (Y, traffic purchase `UNKNOWN_INFORMEES splice_traffic-manager`) |
| 11112 11119 11134 | dup of 10930 (Y, RewardCouponV2 test tag too low) |
| 10934 10937 | X1 and X3 confirmed fixed at PV 36 (ExternalPartySetupProposal + ValidatorOfflineRootNamespaceKey pass in 124756989553) |
