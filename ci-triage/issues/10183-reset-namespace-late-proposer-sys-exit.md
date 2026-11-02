# 10183 - SvStateManagementIntegrationTest teardown: the fourth owner's decentralized-namespace reset proposal arrived after the first three had already authorized it, the plugin re-proposed 15 times in 90 ms and called sys.exit(1); the shard reported nothing (run 35611022158)

Family H, first bullet (`ResetTopologyStatePlugin` `sys.exit(1)` in teardown; 10137 and 10139), with a new cause. sbt
exited 1 at 14:39:16 with no ScalaTest summary. The test log ends at 14:39:14.853 on
`Exceeded max retries for resetting decentralized namespace: 15, giving up`, which is followed in the plugin by
`sys.exit(1)`. The reset is a topology change that needs a threshold of owner signatures: the plugin submits one
proposal per current owner from that owner's participant, sequentially. In this teardown the proposals of sv1, sv3
and sv4 were sequenced together at 14:39:14.569494-.569496 and met the threshold, so the new definition with
owners = {sv1} became the head state (effective 14:39:14.819). sv2's proposal, submitted at 14:39:14.766, was
rejected with `TOPOLOGY_NO_APPROPRIATE_SIGNING_KEY_IN_STORE` because sv2's namespace is no longer an owner in that
head state. The plugin treats every `CommandFailure` as "base serial changed", restarts the whole reset with no delay,
re-reads the still-effective old definition (serial 33 stays effective until .819), re-proposes serial 34 from sv1,
gets `TOPOLOGY_MAPPING_ALREADY_EXISTS`, and repeats 15 times between 14:39:14.766 and .853, then exits the JVM. The
reset had in fact succeeded; waiting 60 ms more would have shown owners = {sv1}. Seven earlier resets in the same
shard took 0.2 to 2.1 s each with no rejected proposal.

- Run: https://github.com/canton-network/splice/actions/runs/35611022158, main 4f5d6220eb ("upgrade the
  observability stack (#7433)"), job 106370913055 `ci / scala_test_wall_clock_time / wall-clock-time (2)`, runner
  `self-hosted-k8s-large-999lg-runner-tjtlj`. The run was still in progress when triaged; a sibling failure,
  `simtime (2)` (106370595499), is triaged separately. Raymond gave refs 10183 and 10184 for this run without a job
  mapping; this packet does not infer it.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824 (`git show 4f5d6220eb:nix/canton-sources.json`).
- Component: test harness (`ResetDecentralizedNamespace` / `ResetTopologyStatePlugin`).

## 1. Classification: sbt exit 1, no report, no flagged line

```
gh api repos/canton-network/splice/actions/jobs/106370913055/logs > log/10183-10184-wct-2/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10183-10184-wct-2/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|ABORTED' | wc -l
sed -E 's/\x1b\[[0-9;]*m//g' log/10183-10184-wct-2/job.log | grep -a -E '^\S+Z \[(info|warn|error)\]' | tail -1 | cut -c1-160
sed -E 's/\x1b\[[0-9;]*m//g' log/10183-10184-wct-2/job.log | grep -a -E '^2026-09-21T14:39:16' | head -3 | cut -c1-160
gh api repos/canton-network/splice/actions/jobs/106370913055 --jq '(.steps[]|select(.conclusion=="failure")|{n:.name,s:.started_at,e:.completed_at})'
```
```
0
2026-09-21T14:35:06.8163114Z [info] *** Test still running after 2 minutes, 29 seconds: suite name: SvStateManagementIntegrationTest, test name: SVs can create a VoteRequest, vote on it and list them..
2026-09-21T14:39:16.3110216Z Attempt 1 failed with exit code 1, retrying
2026-09-21T14:39:16.3111044Z Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
2026-09-21T14:39:16.3159041Z ##[error]Error: failed to run script step (id 1885ec80-b5c9-11f1-9167-f79590e75c87): Error: step failed with return code 1
{"n":"Run Tests","s":"2026-09-21T14:28:07Z","e":"2026-09-21T14:39:46Z"}
```
Shard suites: AmuletExpiryV1FallbackIntegrationTest AutoIgnoreUnresponsivePartiesInMemoryIntegrationTest
CopyVotesIntegrationTest GcpBucketPeriodicBackupIntegrationTest SvOnboardingVettingIntegrationTest
SvReconcileSynchronizerConfigIntegrationTest SvStateManagementIntegrationTest UnsupportedPackageVettingIntegrationTest.
Only SvStateManagementIntegrationTest ran; the other seven were lost.

## 2. Test log: ends in the teardown of the ninth test

```
T=log/10183-10184-wct-2/logs-wall-clock-time-2/canton_network_test.clog.gz
zcat $T | grep -a -E "Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | tail -3 | cut -c1-150
zcat $T | tail -1 | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,120}).*"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/\1 \4 [\3] \2/'
```
```
2026-09-21T14:38:45.522Z Test succeeded: 'SvStateManagementIntegrationTest/At least 3 SVs can vote on changing the Amulet Configuration'
2026-09-21T14:38:45.522Z Starting 'SvStateManagementIntegrationTest/getPartyToParticipant returns the participant for a known party'...
2026-09-21T14:39:14.853Z ERROR [o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] Exceeded max retries for resetting decentralized namespace: 15, giving up
```
The test body itself had finished (its checks run in about a second after `initDso()`); the teardown plugins ran from
14:39:13.99 (`UpdateHistorySanityCheckPlugin`) and the reset from 14:39:14.629.

## 3. The reset, proposal by proposal

```
zcat $T | grep -a -E 'ResetDecentralizedNamespace|Suppressed ERROR: Request failed for remote participant' | grep -a -E 'T14:39:14' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,200}).*"level":"([A-Z]+)".*/\1 \3 \2/; s/\\n/ /g; s/  +/ /g; s/1220[0-9a-f]{60}/../g' | head -9
zcat $T | grep -a -c 'Restarting decentralized namespace reset'
```
```
2026-09-21T14:39:14.629Z INFO Resetting decentralized namespace to contain only sv1
2026-09-21T14:39:14.629Z INFO The following namespaces need to be removed from the decentralized namespace: Set(12207b058d5a..., 1220b91d7cce..., 12206e0bbf15...)
2026-09-21T14:39:14.766Z INFO Suppressed ERROR: Request failed for remote participant for `sv2`, with admin token. GrpcRequestRefusedByServer: NOT_FOUND/TOPOLOGY_NO_APPROPRIATE_SIGNING_KEY_IN_STORE(11,f7e842b5): Could not find an appropriate signing key to issue the topology transaction Request: Propose(Right(DecentralizedNamespaceDefinition(namespace = 12204b28ede1..., threshold = 1, owners = 12206a0cad75...)),List(),Replace,Some(34),...
2026-09-21T14:39:14.766Z INFO Restarting decentralized namespace reset as command failed likely because base serial has changed
2026-09-21T14:39:14.769Z INFO Resetting decentralized namespace to contain only sv1
2026-09-21T14:39:14.769Z INFO The following namespaces need to be removed from the decentralized namespace: Set(12207b058d5a..., 1220b91d7cce..., 12206e0bbf15...)
2026-09-21T14:39:14.776Z INFO Suppressed ERROR: Request failed for remote participant for `sv1`, with admin token. GrpcRequestRefusedAlreadyExists: ALREADY_EXISTS/TOPOLOGY_MAPPING_ALREADY_EXISTS(10,cfa58615): A matching topology mapping authorized with the same keys already exists in this state Request: Propose(Right(DecentralizedNamespaceDefinition(namespace = 12204b28ede1..., threshold = 1, owners = 12206a0cad75...)),List(),Replace,Some(34),...
2026-09-21T14:39:14.776Z INFO Restarting decentralized namespace reset as command failed likely because base serial has changed
2026-09-21T14:39:14.778Z INFO Resetting decentralized namespace to contain only sv1
16
```
Sixteen restarts between 14:39:14.766 and 14:39:14.853: 15 retries plus the give-up line, all inside 90 ms. Every
retry re-read the definition (still serial 33 with four owners, because serial 34 was not effective until .819) and
re-proposed serial 34 from sv1, which already held it.

## 4. Why sv2 had no appropriate signing key: three signatures had already authorized the reset

sv1's and sv2's participants persisted the serial-34 transaction three times within 36 ms, once per added signature,
all with the same sequencing second and an effective time 250 ms later:
```
C=log/10183-10184-wct-2/logs-wall-clock-time-2/canton.clog.gz
zcat $C | grep -a -E '"@timestamp":"2026-09-21T14:39:14' | grep -a -E 'participant=sv[12]Participant' | grep -a 'Persisted topology transactions' | grep -a 'serial = 34' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"Persisted topology transactions \((SequencedTime\([^)]*\)), (EffectiveTime\([^)]*\))\).*signatures = ([^,]*(, [^,)]*)*)[,)].*"logger_name":"[^"]*participant=(sv[12]Participant)[^"]*".*/\1 \6 \2 \3 signatures=\4/; s/1220[0-9a-f]{60}/../g'
```
```
2026-09-21T14:39:14.695Z sv2Participant SequencedTime(2026-09-21T14:39:14.569494Z) EffectiveTime(2026-09-21T14:39:14.819494Z) signatures=12206a0cad75...
2026-09-21T14:39:14.697Z sv1Participant SequencedTime(2026-09-21T14:39:14.569494Z) EffectiveTime(2026-09-21T14:39:14.819494Z) signatures=12206a0cad75...
2026-09-21T14:39:14.708Z sv2Participant SequencedTime(2026-09-21T14:39:14.569495Z) EffectiveTime(2026-09-21T14:39:14.819495Z) signatures=Seq(12206a0cad75..., 12207b058d5a...
2026-09-21T14:39:14.708Z sv1Participant SequencedTime(2026-09-21T14:39:14.569495Z) EffectiveTime(2026-09-21T14:39:14.819495Z) signatures=Seq(12206a0cad75..., 12207b058d5a...
2026-09-21T14:39:14.730Z sv2Participant SequencedTime(2026-09-21T14:39:14.569496Z) EffectiveTime(2026-09-21T14:39:14.819496Z) signatures=Seq(12206a0cad75..., 12207b058d5a..., 1220b91d7cce...
2026-09-21T14:39:14.731Z sv1Participant SequencedTime(2026-09-21T14:39:14.569496Z) EffectiveTime(2026-09-21T14:39:14.819496Z) signatures=Seq(12206a0cad75..., 12207b058d5a..., 1220b91d7cce...
```
12206a0cad75 is sv1's namespace (the one owner that stays), 12207b058d5a and 1220b91d7cce are two of the three to be
removed; sv2's namespace 12206e0bbf15 never signed. With three of four owner signatures the transaction is fully
authorized, so from 14:39:14.730 the participants' topology head state has owners = {sv1}. sv2's proposal at .766
therefore asks sv2's participant to sign with a namespace that is no longer an owner, and the topology manager
answers NOT_FOUND / no appropriate signing key. The plugin's proposals are sequential and the order of the owner
set is arbitrary, so which owner comes fourth, and whether its proposal lands before or after the first three are
sequenced, varies from run to run.

The other seven resets in this shard, for comparison (each about one second, no rejected proposal):
```
zcat $T | grep -a -E 'Resetting decentralized namespace to contain only sv1|decentralized namespace has been reset|Exceeded max retries' | grep -a -oE '"@timestamp":"[^"]+","message":"[^"]{0,60}' | sed -E 's/"@timestamp":"([^"]+)","message":"/\1 /' | awk '{ts=$1; $1=""; k=$0; if (k!=last) {print ts k; last=k}}'
zcat $T | grep -a 'Suppressed ERROR: Request failed for remote participant' | grep -a -c -E 'T14:3[5-8]'
```
```
2026-09-21T14:35:11.151Z Resetting decentralized namespace to contain only sv1
2026-09-21T14:35:12.249Z decentralized namespace has been reset
2026-09-21T14:35:56.536Z Resetting ...    2026-09-21T14:35:57.652Z reset
2026-09-21T14:36:26.422Z Resetting ...    2026-09-21T14:36:26.607Z reset
2026-09-21T14:36:59.286Z Resetting ...    2026-09-21T14:37:01.395Z reset
2026-09-21T14:37:37.763Z Resetting ...    2026-09-21T14:37:38.838Z reset
2026-09-21T14:38:09.456Z Resetting ...    2026-09-21T14:38:10.554Z reset
2026-09-21T14:38:43.753Z Resetting ...    2026-09-21T14:38:44.850Z reset
2026-09-21T14:39:14.629Z Resetting decentralized namespace to contain only sv1
2026-09-21T14:39:14.853Z Exceeded max retries for resetting decentralized namespace: 15, giving up
0
```

## 5. The plugin code that turns one late proposal into a lost shard

```
git show 4f5d6220eb:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala | sed -n '80,108p'
git show 4f5d6220eb:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetDecentralizedNamespace.scala | sed -n '53,70p;86,99p'
```
```
    def resetTopologyStateRetries(retries: Int): Unit = {
      if (retries > MAX_RETRIES) {
        logger.error(s"Exceeded max retries for resetting $topologyType: $MAX_RETRIES, giving up")
        sys.exit(1)
      }
      try {
        resetTopologyState(env, ..., sv1)
      } catch {
        case _: CommandFailure =>
          logger.info(s"Restarting $topologyType reset as command failed likely because base serial has changed")
          resetTopologyStateRetries(retries + 1)
        ...
          def proposeDecentralizedNamespaceReset(client: ParticipantClientReference) = {
            env.environment.loggerFactory.asInstanceOf[SuppressingLogger]
              .assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
                client.topology.decentralized_namespaces.propose(..., serial = Some(existingDecentralizedNamespace.context.serial + PositiveInt.one), synchronize = None).discard,
                forAll(_)(_.message should include("FAILED_PRECONDITION/SERIAL_MISMATCH")),
              )
          }
          existingDecentralizedNamespace.item.owners
            .concat(Set(sv1.participantClientWithAdminToken.id.uid.namespace))
            .foreach { namespace =>
              val sv = usableSvsByNamespace.getOrElse(namespace, throw ...)
              proposeDecentralizedNamespaceReset(sv)
            }
```
Three properties combine: every proposal must succeed although only a threshold of them is needed; any
`CommandFailure` restarts the reset immediately, faster than the 250 ms topology effective delay, so the re-read
state is stale on every retry; and exhaustion ends in `sys.exit(1)` instead of a failed suite. `assertLogsSeq`
(vendored canton 3.5.7 `SuppressingLogger.scala:285-294`, compiled into splice) does not run its assertion when the
block throws, so the SERIAL_MISMATCH expectation is never checked on the failure path.

## 6. The 106 MB canton log is BFT DEBUG chatter, not a signal

```
zcat $C | grep -a -E '"@timestamp":"2026-09-21T14:36' | grep -a -oE '"logger_name":"[^"]{0,75}' | awk '{c[$0]++} END{for(k in c) print c[k], k}' | sort -rn | head -3
zcat $C | grep -a -E '"@timestamp":"2026-09-21T14:(3[3-9]|40)' | grep -a -E '"level":"(WARN|ERROR)"' | grep -a -oE '"logger_name":"[^"]{0,60}' | awk '{c[$0]++} END{for(k in c) print c[k], k}' | sort -rn | head -3
```
```
80056 "logger_name":"c.d.c.s.s.b.b.b.p.g.PekkoP2PGrpcNetworking$PekkoP2PGrpcNetworkManager:seque
21126 "logger_name":"c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv4/psid=
21120 "logger_name":"c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv2/psid=
92 "logger_name":"c.d.c.a.AuthInterceptor:participant=sv2Participant
80 "logger_name":"c.d.c.a.AuthInterceptor:participant=sv4Participant
79 "logger_name":"c.d.c.a.AuthInterceptor:participant=sv3Participant
```
The AuthInterceptor WARNs are `PERMISSION_DENIED ... UserNotFound(svN_validator_user-<env>)` while each isolated
environment's validator users are being created (319 over the shard, 14:32:46 to 14:39:08); they are unrelated
and on the ignore list.

## Verdict

- Family H, first bullet, third occurrence (10137, 10139, this one), with a new trigger: not a slow sequencer but the
  reset succeeding before the last proposer got its turn. Flake, test-harness side. Not a duplicate of an open ref
  as a mechanism; the evidence-loss half is the same as 10137/10139.
- Fix branch `ray/fix-10183-reset-namespace-late-proposer` (5792168c1f, off main 4f5d6220eb; to be renamed
  once the ref is confirmed): `ResetDecentralizedNamespace` now treats a rejected proposal from one owner as
  non-fatal and lets the existing wait loop decide (it succeeds once owners = {sv1} is effective, throws the serial
  changed error if the definition moved elsewhere, and times out as before if the reset really did not happen), and
  the suppressed-error assertion accepts `TOPOLOGY_MAPPING_ALREADY_EXISTS` and `TOPOLOGY_NO_APPROPRIATE_SIGNING_KEY_IN_STORE`
  next to `SERIAL_MISMATCH`. One file, +13/-3. Independent of `ray/fix-reset-topology-plugin-no-exit` (743babe3f0),
  which replaces the `sys.exit(1)` in `ResetTopologyStatePlugin` with a suite failure; both are needed, they touch
  different files. Verified: ASCII-only diff, `apps-app/Test/scalafmtCheck` (result in the README row). NOT compiled
  or run.
- Not verified: the threshold value of the four-owner definition (the log only prints the new one-owner definition);
  the three-signature acceptance and the effective owners = {sv1} state are observed directly.
