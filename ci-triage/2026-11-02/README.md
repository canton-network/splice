# 2026-11-02: inbox triage

## Infra (fork C): `infra-c.md`, `host-commands-c.md`

| Issue | Job | Verdict |
|---|---|---|
| 11117 | cni Devnet Backup Status 41175569098, 10-31 06:00 | first night after the Devnet LSU switch to serial 7 (cni #7836/#7837); stale Cloud SQL backup at 06:00, recovered by 11-01; stays open, needs-log, Krzysztof |
| 11118 | cni Devnet Backup 41166827191, 10-31 04:00 | dup of 11117 (same night, failed ~07:10) |
| 11106 | CircleCI 87744 deploy_sv_reonboard, 10-30 16:00 | not 10844 (deployed splice contains #8125); intermittent, needs-log job 527403 |
| 11137 | CircleCI 87814 deploy_sv_reonboard, 11-02 08:00 | same as 11106 until the logs show otherwise; needs-log job 527732 |
