# 10612 - "Token refresh failed: event executor terminated" from a BFT standalone sequencer during shutdown (run 40313835584)

Same family as 10147 (BFT P2P layer racing the sequential shutdown of standalone sequencers in one JVM). Post-merge
main, all tests passed (ParticipantKmsIdentitiesIntegrationTest shard), checkErrors flagged one WARN in
`canton-standalone-kms-identities-sv_after_shutdown.clog`. While the standalone canton closed, sv1's BFT
`P2PGrpcConnectionManager` could not close because of an active `p2p-create-peer-sender`; that sender kept
re-authenticating to a peer that was already gone (UNAVAILABLE every 0.5 s) and finally scheduled a token refresh on
a gRPC channel whose netty event executor had terminated.

- Run: https://github.com/canton-network/splice/actions/runs/40313835584, main bb5b8554a9 (#7818), job 121541859078
  `wall-clock-time (9)`.
- Runtime canton: 3.7.0-snapshot.20261023.20808.0.vf4863aea.
- Component: Canton BFT orderer P2P (shutdown), standalone-canton log.

## 1. Flagged line

```
gh api repos/canton-network/splice/actions/jobs/121541859078/logs > log/10612/job.log
s=$(grep -a -n -m1 'Found problems in' log/10612/job.log | cut -d: -f1); e=$(grep -a -n -m1 'contains problems' log/10612/job.log | cut -d: -f1)
sed -n "${s},${e}p" log/10612/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' | sed -E 's/^[^Z]*Z //' | cut -c1-560
```
```
***"@timestamp":"2026-10-26T11:32:33.797Z","message":"Token refresh failed","logger_name":"c.d.c.s.a.g.AuthenticationTokenManager:sequencer=sv1StandaloneSequencer/psid=global-domain::1220bf493c6e::37-0","thread_name":"canton-env-ec-135","level":"WARN","stack_trace":"java.util.concurrent.RejectedExecutionException: event executor terminated\n\tat io.grpc.netty.shaded.io.netty.util.concurrent.SingleThreadEventExecutor.reject(SingleThreadEventExecutor.java:934)\n\tat io.grpc.netty.shaded.io.netty.util.concurrent.SingleThreadEventExecutor.offerTask(SingleThr
```

## 2. What the sequencer was doing in the seconds before (after_shutdown log)

```
unzip -p log/10612/a.zip canton-standalone-kms-identities-sv_after_shutdown.clog.gz | gunzip -c | grep -a -E 'T11:32:3[0-4]' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/[0-9a-f]{60,}//g' | cut -c1-200 | grep -a -i -E "token|clos|shut|stop|WARN" | head -15
```
Selected lines (the same three-line retry repeats every 0.5 s):
```
2026-10-26T11:32:30.188Z Now retrying operation 'generate sequencer authentication token'. ",[c.d.c.s.a.AuthenticationTokenProvider:sequencer=sv1StandaloneSequencer/psid=global-domain::1220bf493c6e::3
2026-10-26T11:32:30.189Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.a.AuthenticationTokenProvider:sequencer=sv1StandaloneSequencer/psid=global-domain::1220bf493c6e:
2026-10-26T11:32:30.190Z The operation 'generate sequencer authentication token' was not successful. Retrying after 0.5s. Result: Outcome(Left(Status{code=UNAVAILABLE, description=io exception, cause=
2026-10-26T11:32:30.326Z At least 1 active readers prevent closing. Next log message in 500ms. Active readers: p2p-create-peer-sender",[c.d.c.s.s.b.b.b.p.g.P2PGrpcConnectionManager:sequencer=sv1Standa
```

## 3. Not covered by an ignore pattern

```
git grep -n -i -E "RejectedExecution|event executor terminated|Token refresh" cn/main -- project/ignore-patterns
```
```
cn/main:project/ignore-patterns/canton_log.ignore.txt:86:Token refresh failed with Status.*UNAVAILABLE.*Health-check stream unexpectedly closed
cn/main:project/ignore-patterns/canton_log_simtime_extra.ignore.txt:34:Request failed for sequencer.*GrpcServiceUnavailable: .*/Token refresh aborted due to shutdown
```

## Verdict

Shutdown race in Canton's BFT P2P layer, same family as 10147 (Canton should not WARN for work rejected by an
executor that was closed as part of its own shutdown). Flake, after-shutdown half of a standalone log. Candidate for
an after-shutdown-only ignore (`canton_log_shutdown_extra.ignore.txt`) since it can only appear once the node is
closing; not proposed here because 10147 is still open with the same question. Not verified: frequency.
