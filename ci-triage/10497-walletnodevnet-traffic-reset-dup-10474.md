# 10497 - WalletNoDevNetIntegrationTest SEQUENCER_NOT_ENOUGH_TRAFFIC_CREDIT WARNs from aliceParticipant (run 39966442655)

DUPLICATE of 10474 (open, pawelperek-da; fix PR #7923 open). Main c8542ad58a (#7909), job `wall-clock-time (7)`,
canton 3.7.0-snapshot.20261022.20797.0.vd0a4e6f2. Tests: succeeded 37, failed 0. checkErrors flagged 5 WARNs in
`log/canton_before_shutdown.clog`.

- Run: https://github.com/canton-network/splice/actions/runs/39966442655, main c8542ad58a (#7909), job 120458028680
  `wall-clock-time (7)`.
- Component: test (WalletNoDevNetIntegrationTest preAppStart traffic reset).

## 1. Flagged lines

```
gh api repos/canton-network/splice/actions/jobs/120458028680/logs > log/10497/job.log
sed -n 13626,13630p log/10497/job.log | grep -o '"logger_name":"[^"]*"' | sort | uniq -c
sed -n 13626p log/10497/job.log | cut -c1-330
```
```
      5 "logger_name":"c.d.c.p.p.TransactionProcessor:participant=aliceParticipant/psid=global-domain::12201783a7f9::37-0"
***"@timestamp":"2026-10-23T14:33:45.949Z","message":"Failed to submit transaction due to Error(RequestRefused(\n  SendAsyncErrorGrpc(\n    Request failed for server-SEQ::sv1::1220bce8f6b7.... GrpcRequestRefusedByServer: FAILED_PRECONDITION/SEQUENCER_NOT_ENOUGH_TRAFFIC_CREDIT(9,4ba2f781): Submission was rejected because no traffic is available: AboveTrafficLimit(\n  member = PAR::aliceValidator::1220c9245381...,
```

## 2. WalletNoDevNetIntegrationTest was starting at that time

```
grep -a -n -E "\*\*\* Test still running" log/10497/job.log | grep -a -E "T14:3[0-5]" | cut -c1-200
```
```
2026-10-23T14:34:34 [info] *** Test still running after 1 minute, 20 seconds: suite name: WalletNoDevNetIntegrationTest, test name: A validator wallet should accept transfers with high p
```

## Verdict

Duplicate of 10474 (same suite, same participant, same SEQUENCER_NOT_ENOUGH_TRAFFIC_CREDIT WARN during start-up after
the preAppStart traffic reset). Fix in review: #7923 "Fix WalletNoDevNet traffic reset starving start-up submissions".
