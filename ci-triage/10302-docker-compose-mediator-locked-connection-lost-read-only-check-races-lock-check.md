# 10302 - docker-compose (1): `Locked connection was lost` WARN on globalMediatorSv2 after three read-only probes lose the race for the connection to the DB lock check (run 37634008083)

New (family V). main d8b78a11ca, job `docker-compose (1)`, Canton 3.6.1. The only test
(`DockerComposeValidatorFrontendIntegrationTest`) passed; checkErrors failed on one canton WARN,
`DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1 Locked connection was lost, trying to rebuild` at
14:39:55.691, during Canton startup and 8.5 min before the test suite started. The connection was healthy: Canton's
periodic connection check (`DbLockedConnection.checkConnection`, 3.6.1 `DbLockedConnection.scala:649-699`) marks the
single `KeepAliveConnection` in use, runs `isValid`, marks it free, and only then runs the read-only probe, which has
to take the connection again. Each time, the node's `DbLockPostgres: checking lock` ran in the same millisecond and
held the connection (the DB lock is created on the same `KeepAliveConnection`), so the probe got
`NoConnectionAvailable`, which `checkConnection` classes as `Indeterminate`. At 14:39:44.574, 14:39:49.873 and
14:39:55.689 that happened three periods in a row; the third reached `maxInconclusiveReadOnlyChecks` (default 3,
`DbLockedConnection.scala:429-432`), and `onConnectionLost` logged the WARN. The rebuild reconnected in 80 ms. The
same collision happened once each on sv3Participant and sv1Participant in this run without reaching the threshold.
Canton-side flake, no splice change.

- Run: https://github.com/canton-network/splice/actions/runs/37634008083, main d8b78a11ca ("[ci] Bump shell-quote from 1.8.4 to 1.12.0 in /cluster/pulumi (#7660)"), job 112836149816 `ci / scala_test_docker_compose / docker-compose (1)`.
- Runtime canton: 3.6.1 (`nix/canton-sources.json` at d8b78a11ca; release tarball hash matches the pin, section 5).
- Component: Canton `com.digitalasset.canton.resource.DbLockedConnection` (connection health check vs DB lock check).

Setup, from the repo root:
```
REF=10302; RUN=37634008083; mkdir -p log/$REF
gh api repos/canton-network/splice/actions/jobs/112836149816/logs > log/$REF/job.log
export TMPDIR=$PWD/log/ghtmp; mkdir -p $TMPDIR
gh run download $RUN --repo canton-network/splice -n logs-docker-compose-1 -D log/$REF/logs-docker-compose-1
L=log/$REF/job.log; A=log/$REF/logs-docker-compose-1; C=$A/canton_before_shutdown.clog.gz; T=$A/canton_network_test.clog.gz
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
```
The run had exactly one failed job, so the (run, job, ref) tuple from the user needs no mapping:
```
gh run view $RUN --repo canton-network/splice --json jobs --jq '.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{id:.databaseId,name:.name,c:.conclusion}'
```
```
{"c":"failure","id":112836149816,"name":"ci / scala_test_docker_compose / docker-compose (1)"}
```

## 1. Classification: all tests pass, checkErrors flags one canton WARN

```
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Process completed with exit code 1.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[info] All tests passed.
[info] Run completed in 12 minutes, 39 seconds.
[info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
```
Flagged lines without the ignored ones:
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' $L | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in check-sbt-output' | cut -c1-600
```
```
Found problems in log/canton_before_shutdown.clog:
{"@timestamp":"2026-10-07T14:39:55.691Z","message":"Locked connection was lost, trying to rebuild","logger_name":"c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1","thread_name":"canton-env-ec-51","level":"WARN"}
Total: 1 lines with problems.
```
checkErrors stops at the first failing file (family J note). The files it did not reach are clean
(`canton-standalone-*` are not in the artifact):
```
for f in canton_network_test canton_after_shutdown; do zcat $A/$f.clog.gz > log/$REF/$f.clog; done
.github/actions/scripts/check-logs.sh log/$REF/canton_network_test.clog project/ignore-patterns/canton_network_test_log.ignore.txt > log/$REF/cnt.out 2>&1; echo "exit=$?"; grep -E '^(No problems|Found problems|Total:)' log/$REF/cnt.out
.github/actions/scripts/check-logs.sh log/$REF/canton_after_shutdown.clog project/ignore-patterns/canton_log.ignore.txt project/ignore-patterns/canton_log_bft.ignore.txt project/ignore-patterns/canton_log_shutdown_extra.ignore.txt > log/$REF/after.out 2>&1; echo "exit=$?"; grep -E '^(No problems|Found problems|Total:)' log/$REF/after.out
```
```
exit=0
Total: 1197 lines with ignored entries.
No problems found in log/10302/canton_network_test.clog.
Total: 67 lines with stack traces.
exit=0
No problems found in log/10302/canton_after_shutdown.clog.
Total: 0 lines with stack traces.
```

## 2. No test was running: the WARN is 3 min after Canton start, 8.5 min before the suite

```
{ zcat $C | head -1 | sed -E "$F"; zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170; }
```
```
2026-10-07T14:36:54.234Z Starting Canton version 3.6.1",[c.d.canton.CantonCommunityApp$] INFO
2026-10-07T14:48:17.751Z Starting test suite 'DockerComposeValidatorFrontendIntegrationTest'...",
2026-10-07T14:48:19.766Z Starting 'DockerComposeValidatorFrontendIntegrationTest/docker-compose based validator works'...",
2026-10-07T15:00:56.964Z Test succeeded: 'DockerComposeValidatorFrontendIntegrationTest/docker-compose based validator works'",
```

## 3. pool-1 of globalMediatorSv2: three failed read-only probes 5 s apart, then the WARN and an 80 ms rebuild

```
zcat $C | grep -a 'globalMediatorSv2/connId=pool-1' | grep -a -E 'T14:39:(4[0-9]|5[0-9])' | sed -E "$F" | cut -c1-260
```
```
2026-10-07T14:39:44.574Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@5bdf8d59 is read-only: Failed to check new connection for read-only: com.digitalasset.canton.resource.DbStorage$NoConnectionAvailable: No free connectio
2026-10-07T14:39:49.873Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@5bdf8d59 is read-only: Failed to check new connection for read-only: com.digitalasset.canton.resource.DbStorage$NoConnectionAvailable: No free connectio
2026-10-07T14:39:55.689Z Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@5bdf8d59 is read-only: Failed to check new connection for read-only: com.digitalasset.canton.resource.DbStorage$NoConnectionAvailable: No free connectio
2026-10-07T14:39:55.691Z Locked connection was lost, trying to rebuild",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1] WARN
2026-10-07T14:39:55.691Z Trying to close defunct connection com.digitalasset.canton.resource.KeepAliveConnection@5bdf8d59 before recovery",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1] DEBUG
2026-10-07T14:39:55.771Z Succeeded to reconnect to database",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1] DEBUG
2026-10-07T14:39:55.773Z Creating new DB lock with com.digitalasset.canton.resource.KeepAliveConnection@4ba91074",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1] DEBUG
2026-10-07T14:39:55.773Z Trying to become active..",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1] DEBUG
2026-10-07T14:39:55.774Z Successfully rebuilt connection",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1] INFO
2026-10-07T14:39:55.774Z Successfully finished locked connection rebuild",[c.d.c.r.DbLockedConnection:mediator=globalMediatorSv2/connId=pool-1] DEBUG
```
`Creating new DB lock with ...KeepAliveConnection@...` shows the DB lock runs on the same single connection as the
probe. The exception comes from the `KeepAliveConnection` session factory, not from Postgres:
```
zcat $C | grep -a 'T14:39:55.689Z' | grep -a 'read-only' | sed 's/\\n\\t/\n  /g; s/\\n/\n/g' | sed -n 1,4p | cut -c1-200
```
```
{"@timestamp":"2026-10-07T14:39:55.689Z","message":"Failed to check if connection com.digitalasset.canton.resource.KeepAliveConnection@5bdf8d59 is read-only: Failed to check new connection for read-on
  at com.digitalasset.canton.resource.KeepAliveConnection$$anon$1.createConnection(KeepAliveConnection.scala:50)
  at slick.jdbc.JdbcBackend$BaseSession.<init>(JdbcBackend.scala:517)
  at slick.jdbc.JdbcBackend$JdbcDatabaseDef.createSession(JdbcBackend.scala:49)
```

## 4. Every failed probe in the run starts within 1-66 ms after a lock check on the same node

All failed read-only probes in the run (five), each with the two preceding check starts on the same node:
```
zcat $C | grep -a -E 'Failed to check if connection|Running queued action: com.digitalasset.canton.resource.(DbLockedConnection: checking connection|DbLockPostgres: checking lock)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(Running queued action: com.digitalasset.canton.resource.)?([^",]{0,40}).*"logger_name":"[^:]*:([^"]*)".*/\1 \4 \3/' > log/$REF/checks.txt
grep -n 'Failed to check' log/$REF/checks.txt | while IFS=: read n rest; do node=$(echo "$rest" | awk '{print $2}' | cut -d/ -f1); echo "== $rest"; sed -n "$((n-12)),$((n-1))p" log/$REF/checks.txt | grep -F "$node " | tail -2; done
```
```
== 2026-10-07T14:39:44.574Z mediator=globalMediatorSv2/connId=pool-1 Failed to check if connection com.digita
2026-10-07T14:39:44.571Z mediator=globalMediatorSv2 DbLockedConnection: checking connection
2026-10-07T14:39:44.572Z mediator=globalMediatorSv2 DbLockPostgres: checking lock
== 2026-10-07T14:39:49.873Z mediator=globalMediatorSv2/connId=pool-1 Failed to check if connection com.digita
2026-10-07T14:39:49.870Z mediator=globalMediatorSv2 DbLockPostgres: checking lock
2026-10-07T14:39:49.870Z mediator=globalMediatorSv2 DbLockedConnection: checking connection
== 2026-10-07T14:39:55.689Z mediator=globalMediatorSv2/connId=pool-1 Failed to check if connection com.digita
2026-10-07T14:39:55.688Z mediator=globalMediatorSv2 DbLockedConnection: checking connection
2026-10-07T14:39:55.688Z mediator=globalMediatorSv2 DbLockPostgres: checking lock
== 2026-10-07T14:43:59.285Z participant=sv3Participant/connId=pool-0 Failed to check if connection com.digita
2026-10-07T14:43:59.273Z participant=sv3Participant DbLockPostgres: checking lock
2026-10-07T14:43:59.274Z participant=sv3Participant DbLockedConnection: checking connection
== 2026-10-07T14:46:47.171Z participant=sv1Participant/connId=pool-0 Failed to check if connection com.digita
2026-10-07T14:46:47.105Z participant=sv1Participant DbLockPostgres: checking lock
2026-10-07T14:46:47.108Z participant=sv1Participant DbLockedConnection: checking connection
```
On globalMediatorSv2 the connection check and a lock check fired in the same ms (+-1 ms) three periods in a row.
sv3Participant and sv1Participant had one collision each, below the threshold, so no WARN.

## 5. Canton 3.6.1 jar: the probe runs after `markFree`, `NoConnectionAvailable` is Indeterminate, 3 in a row is "lost"

Jar is the pinned one:
```
git show d8b78a11ca:nix/canton-sources.json | grep -E '"(version|oss_sha256)"'
curl -sSLo log/canton-jars/canton-3.6.1.tgz https://www.canton.io/releases/canton-open-source-3.6.1.tar.gz
nix-hash --type sha256 --flat --base32 log/canton-jars/canton-3.6.1.tgz
tar -C log/canton-jars -xzf log/canton-jars/canton-3.6.1.tgz --wildcards '*/lib/canton-open-source-*.jar'
J=log/canton-jars/canton-open-source-3.6.1/lib/canton-open-source-3.6.1.jar
```
```
  "version": "3.6.1",
  "oss_sha256": "sha256:1vmzlcmzf4c8zw9jmaij014p9wjhv00wap09g8xcn9q34y350a25",
1vmzlcmzf4c8zw9jmaij014p9wjhv00wap09g8xcn9q34y350a25
```
`KeepAliveConnection` hands out its one connection only if `markInUse` (an `AtomicBoolean.compareAndSet`) succeeds,
else throws `NoConnectionAvailable` (3.6.1 `KeepAliveConnection.scala:47-50`):
```
javap -p -c -l -cp $J 'com.digitalasset.canton.resource.KeepAliveConnection$$anon$1' | awk '/createConnection\(\)/{p=1} p&&/^$/{exit} p' | grep -E 'invoke|new |athrow|ifeq|line ' | sed -E 's/ +/ /g; s/\/\/ //'
```
```
 4: invokevirtual #42 Method com/digitalasset/canton/resource/KeepAliveConnection.markInUse:()Z
 7: ifeq 15
 15: new #14 class com/digitalasset/canton/resource/DbStorage$NoConnectionAvailable
 19: invokespecial #46 Method com/digitalasset/canton/resource/DbStorage$NoConnectionAvailable."<init>":()V
 22: athrow
 line 47: 0
 line 48: 10
 line 50: 15
```
`checkConnection` with source lines from the LineNumberTable (script in the scratch note below): `markInUse` (649,
else skip as in use, 698-699), `isValid` under `blocking` (657), `markFree` (663), only then the read-only probe
`Try(...)` (672-673); `Left` maps to `Indeterminate` (683-685), not-valid to `Unhealthy` (694-695):
```
javap -p -c -l -cp $J 'com.digitalasset.canton.resource.DbLockedConnection$' | awk '/checkConnection\(com/{p=1} p&&/^$/{exit} p' | python3 -I log/$REF/bc-lines.py 0 999 'markInUse|markFree|blocking|Try\$\.apply|instanceof .*(Left|Failure)|ConnectionHealth\$'
```
```
DbLockedConnection.scala:649 @1 invokevirtual #159 Method com/digitalasset/canton/resource/KeepAliveConnection.markInUse:()Z
DbLockedConnection.scala:657 @84 invokevirtual #233 Method scala/concurrent/package$.blocking
DbLockedConnection.scala:663 @96 invokevirtual #242 Method com/digitalasset/canton/resource/KeepAliveConnection.markFree:()V
DbLockedConnection.scala:663 @103 invokevirtual #242 Method com/digitalasset/canton/resource/KeepAliveConnection.markFree:()V
DbLockedConnection.scala:673 @202 invokevirtual #267 Method scala/util/Try$.apply
DbLockedConnection.scala:679 @329 getstatic #289 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Unhealthy$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Unhealthy$;
DbLockedConnection.scala:682 @451 getstatic #295 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Healthy$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Healthy$;
DbLockedConnection.scala:683 @481 instanceof #297 class scala/util/Left
DbLockedConnection.scala:685 @569 getstatic #314 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$;
DbLockedConnection.scala:686 @581 instanceof #316 class scala/util/Failure
DbLockedConnection.scala:688 @671 getstatic #314 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$;
DbLockedConnection.scala:691 @758 getstatic #314 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$;
DbLockedConnection.scala:695 @838 getstatic #289 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Unhealthy$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Unhealthy$;
DbLockedConnection.scala:699 @905 getstatic #295 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Healthy$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Healthy$;
```
The logged message is the `Left` branch, so `isValid` had passed on each of the three probes:
```
javap -v -cp $J 'com.digitalasset.canton.resource.DbLockedConnection$' | grep -E '= String .*(read-only|is valid|NOT valid|in use)' | sed -E 's/ +/ /g'
```
```
 #176 = String #175 // Checking if connection \u0001 is valid
 #244 = String #243 // Connection \u0001 is valid, checking if connection is read-only
 #282 = String #281 // Connection \u0001 is read-only
 #291 = String #290 // Connection \u0001 is not read-only
 #305 = String #304 // Failed to check if connection \u0001 is read-only: \u0001
 #337 = String #336 // Connection \u0001 is NOT valid
 #340 = String #339 // Skip connection \u0001 check because the connection is in use
 #723 = String #722 // connection check read-only
 #1473 = String #1472 // Failed to check new connection for read-only:
```
`checkLockedConnection`: `Indeterminate` increments the counter and calls `onConnectionLost` (the WARN) once it
reaches `maxInconclusiveReadOnlyChecks` (3.6.1 `DbLockedConnection.scala:429-432`):
```
javap -p -c -l -cp $J com.digitalasset.canton.resource.DbLockedConnection | awk '/void checkLockedConnection\$1\(/{p=1} p&&/^$/{exit} p' | python3 -I log/$REF/bc-lines.py 502 545 '.'
```
```
DbLockedConnection.scala:429 @502 getstatic #1907 Field com/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$.MODULE$:Lcom/digitalasset/canton/resource/DbLockedConnection$ConnectionHealth$Indeterminate$;
DbLockedConnection.scala:429 @505 aload 14
DbLockedConnection.scala:429 @507 invokevirtual #1017 Method java/lang/Object.equals
DbLockedConnection.scala:429 @510 ifeq 632
DbLockedConnection.scala:430 @513 iload_2
DbLockedConnection.scala:430 @514 iconst_1
DbLockedConnection.scala:430 @515 iadd
DbLockedConnection.scala:430 @516 istore 15
DbLockedConnection.scala:431 @518 iload 15
DbLockedConnection.scala:431 @520 aload_0
DbLockedConnection.scala:431 @521 getfield #847 Field config:Lcom/digitalasset/canton/config/DbLockedConnectionConfig;
DbLockedConnection.scala:431 @524 invokevirtual #1910 Method com/digitalasset/canton/config/DbLockedConnectionConfig.maxInconclusiveReadOnlyChecks:()I
DbLockedConnection.scala:431 @527 if_icmplt 546
DbLockedConnection.scala:432 @530 aload_0
DbLockedConnection.scala:432 @531 aload_3
DbLockedConnection.scala:432 @532 aload 11
DbLockedConnection.scala:432 @534 aload 12
DbLockedConnection.scala:432 @536 invokespecial #1896 Method onConnectionLost$1
DbLockedConnection.scala:432 @539 getstatic #908 Field scala/runtime/BoxedUnit.UNIT:Lscala/runtime/BoxedUnit;
DbLockedConnection.scala:432 @542 pop
DbLockedConnection.scala:432 @543 goto 645
```
Defaults: connection health check and lock health check both every 5 s (same period, so a collision can repeat),
threshold 3. Splice does not override them:
```
for c in DbLockedConnectionConfig DbLockConfig; do javap -p -cp $J com.digitalasset.canton.config.$c | grep 'private final' | sed -E 's/.* ([a-zA-Z]+);/\1/' | nl -w2 -s' ' | sed "s/^/$c param /"; javap -p -c -cp $J "com.digitalasset.canton.config.$c\$" | awk '/lessinit\$greater\$default\$[0-9]+\(\)/{n=$0; sub(/.*default\$/,"",n); sub(/\(.*/,"",n)} /ldc2_w|iconst_|bipush/ && n!=""{v=$0; sub(/.*\/\/ /,"",v); sub(/.*: +/,"",v); print "'$c' default$" n " = " v; n=""}'; done | grep -E 'param ( 1| 2|12) |default\$(1|2|12) '
git grep -n -i -E 'max-inconclusive|inconclusive-read-only|health-check-period' -- ':!canton' | wc -l
```
```
DbLockedConnectionConfig param  1 passiveCheckPeriod
DbLockedConnectionConfig param  2 healthCheckPeriod
DbLockedConnectionConfig param 12 maxInconclusiveReadOnlyChecks
DbLockedConnectionConfig default$1 = long 15l
DbLockedConnectionConfig default$2 = long 5l
DbLockedConnectionConfig default$12 = iconst_3
DbLockConfig param  1 healthCheckPeriod
DbLockConfig param  2 healthCheckTimeout
DbLockConfig default$1 = long 5l
DbLockConfig default$2 = long 15l
0
```
(The durations are built with `PositiveFiniteDuration.ofSeconds`.) The same code is in the two previous pins
(3.6.0-snapshot.20260929 and .20261001: `maxInconclusiveReadOnlyChecks` and the message are present), so this is
not a 3.6.1 regression.

`log/$REF/bc-lines.py` (maps bytecode offsets to the LineNumberTable; args: from-offset, to-offset, regex):
```
import sys,re
lo,hi,pat=int(sys.argv[1]),int(sys.argv[2]),sys.argv[3]
code=[];lines={}
for l in sys.stdin:
    m=re.match(r"\s+line (\d+): (\d+)",l)
    if m: lines[int(m.group(2))]=int(m.group(1)); continue
    m=re.match(r"\s+(\d+): (.*)",l)
    if m: code.append((int(m.group(1)),m.group(2)))
starts=sorted(lines)
def src(o):
    s=[k for k in starts if k<=o]; return lines[s[-1]] if s else None
for o,ins in code:
    if lo<=o<=hi and re.search(pat,ins):
        ins=re.sub(r"\s+"," ",ins); ins=re.sub(r":\(L.*","",ins); ins=re.sub(r"// ","",ins)
        print(f"DbLockedConnection.scala:{src(o)} @{o} {ins}")
```

## 6. Not family J (DB write stalls) or a lost Postgres

No `DB_CONNECTION_LOST`, no `LockCheckRejected` spike (family J: 20-50 per minute), and the only WARN/ERROR loggers
are the ignored AuthInterceptor lines and this one:
```
zcat $C | grep -a 'LockCheckRejected' | grep -a -oE '^\{"@timestamp":"[^"]{16}' | cut -c15- | sort | uniq -c | tr -s ' ' | tr '\n' ' '; echo
zcat $C | grep -a -c -E 'DB_CONNECTION_LOST|Connection is not available'
zcat $C | grep -a -E '"level":"(WARN|ERROR)"' | grep -a -oE '"logger_name":"[^":/]*' | sort | uniq -c | sort -rn
```
```
 1 "2026-10-07T14:38  3 "2026-10-07T14:39  3 "2026-10-07T14:43  1 "2026-10-07T14:45  1 "2026-10-07T14:46  2 "2026-10-07T14:49  1 "2026-10-07T14:55  1 "2026-10-07T14:56 
0
     74 "logger_name":"c.d.c.a.AuthInterceptor
      1 "logger_name":"c.d.c.r.DbLockedConnection
```

## Verdict

- New, family V. Flake, Canton side: a race inside `DbLockedConnection` between its own connection health check and
  the DB lock check on the same single `KeepAliveConnection`, both every 5 s. The connection was valid each time
  and the rebuild took 80 ms; no node, test or splice app was affected.
- Fix location: Canton (described, for the Canton owner). Either keep the connection marked in use across the
  `isValid` and read-only probe (one `markInUse` for both), or treat `NoConnectionAvailable` from the probe like the
  existing "Skip connection check because the connection is in use" branch (`Healthy`, counter not incremented).
- No splice fix. A log-ignore for `Locked connection was lost` is rejected: the same WARN is the only signal of a real
  connection loss. Raising `max-inconclusive-read-only-checks` in the CI Canton config would make three aligned
  collisions rarer but not remove the race, and would delay real read-only detection; not proposed.
- Resolution: rerun.
- Not verified: that the lock check starting in the same ms is pool-1's own lock (the `Running queued action` lines
  carry no `connId`, and pool-1's connection also serves the pool's queries; globalMediatorSv2 logged nothing but
  health and lock checks in 14:39:30-14:39:56, which only shows the absence of logged activity); why the connection check and lock check stayed aligned to the millisecond for
  three periods; how often this WARN occurs in other runs.
