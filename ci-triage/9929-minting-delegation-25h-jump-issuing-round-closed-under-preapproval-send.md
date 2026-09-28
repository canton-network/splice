# 9929 - WalletMintingDelegationTimeBasedIntegrationTest: after advancing 25 h, ExpireIssuingMiningRoundTrigger closes the IssuingMiningRound that alice's transfer-preapproval send references; LOCAL_VERDICT_INACTIVE_CONTRACTS (run 33638801492)

DUPLICATE of cn-test-failures 10060 (public splice issue #7223), FIXED on main by #7261 (0c43730f70,
2026-09-16). This run is on main 79e56f457f from 2026-09-02, two weeks before the fix; same failure as 10154,
10166 and 10171, which were release-line runs without the backport. No new fix needed.

- Run: https://github.com/canton-network/splice/actions/runs/33638801492, main 79e56f457f ("remove split sv config as
  it becomes default (#7071)"), post-merge CI, job 100276792474 `ci / scala_test_sim_time / simtime (2)`.
- Runtime canton: 3.5.16-snapshot.20260901.19217.0.v1ed99f4c (`nix/canton-sources.json` at 79e56f457f).
- Component: test (sim-time jump too large for the round automation).
- 14 tests in 10 suites, 1 failed: `WalletMintingDelegationTimeBasedIntegrationTest / MintingDelegationCollectRewardsTrigger
  should collect rewards for all coupons owned by the beneficiary` with `CommandFailure: Command execution failed.`

## 1. Failing assertion and shard

The job log has one failed test and a complete ScalaTest summary (no evidence loss); checkErrors was skipped
because the test step failed.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/9929/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id b847a030-a6d6-11f1-bac6-e7e76a3e8a7d): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - collect rewards for all coupons owned by the beneficiary *** FAILED ***
[info] - should collect rewards for all coupons owned by the beneficiary *** FAILED ***
[info] Run completed in 15 minutes, 57 seconds.
[info] Tests: succeeded 13, failed 1, canceled 0, ignored 1, pending 0
```

## 2. The failing clue (same signature as 10154)

```
zcat log/9929/logs-simtime-2/canton_network_test.clog.gz | grep -a -E 'T14:1(4:[34]|5:0|5:1[0-2])' | grep -a -E '"level":"ERROR"|clue:' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c12-330 \
  | grep -a -E 'Advance past|PT25H|delayed development|2x limit|HttpCommandException|Test failed'
```
```
14:15:08.912Z Running clue: All reward contracts except the delayed development fund coupon should be consumed", DEBUG
14:15:08.916Z Finished clue: All reward contracts except the delayed development fund coupon should be consumed", DEBUG
14:15:08.938Z Running clue: (act) Advance past the delayed coupon's mintAfter", DEBUG
14:15:08.939Z Running clue: attempting to advance time by PT25H", DEBUG
14:15:10.509Z Finished clue: attempting to advance time by PT25H", DEBUG
14:15:10.509Z Finished clue: (act) Advance past the delayed coupon's mintAfter", DEBUG
14:15:10.509Z Running clue: (check) The delayed development fund coupon is collected", DEBUG
14:15:12.037Z Finished clue: (check) The delayed development fund coupon is collected", DEBUG
14:15:12.037Z Running clue: Test that amulets get merge at 2x limit", DEBUG
14:15:12.915Z org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 404 Not Found POST at '/api/validator/v0/wallet/transfer-preapproval/send' on 127.0.0.1:5503. Command failed, message: LOCAL_VERDICT_INACTIVE_CONTRACTS(11,771b597b): Rejected transaction is referring to inactive contract
14:15:12.915Z Failed clue: Test that amulets get merge at 2x limit", ERROR
14:15:12.919Z Test failed: 'WalletMintingDelegationTimeBasedIntegrationTest/MintingDelegationCollectRewardsTrigger should collect rewards for all coupons owned by the beneficiary', message: Command execution failed., location: SeeStackDepthException", ERROR
```

The jump: `advancing time by PT25H to 1970-01-02T04:02:39.999Z` at 14:15:10.440 (same line in 10166).

## 3. The inactive contract is an IssuingMiningRound

The rejection's `grpc-status-details-bin` names the contract:

```
zcat log/9929/logs-simtime-2/canton_network_test.clog.gz | grep -a -m1 'tid:771b597b.*failed with NOT_FOUND' \
  | grep -oE 'grpc-status-details-bin=[A-Za-z0-9+/=]+' | cut -d= -f2- | base64 -d | tr -c '[:print:]' '\n' | grep -a -E '.{6,}' | head -8
```
```
gLOCAL_VERDICT_INACTIVE_CONTRACTS(11,771b597b): Rejected transaction is referring to inactive contracts
(type.googleapis.com/google.rpc.ErrorInfo
 LOCAL_VERDICT_INACTIVE_CONTRACTS
participant
aliceParticipant
CONTRACT_ID
List(00cb078b6ef81a2ab910c04cb2ca1a532a810d23af948c7dbbe5fe7339a9e1372fca121220b1d7890aa9f5e1a080a906376a2236993100f5b090b33347e1928899e3840170)
reported_by_participant_id
```

Its creation (sv1Scan ingestion) and sv1Scan serving it as an issuing round shortly before the send:

```
CID=00cb078b6ef81a2ab910c04cb2ca1a532a810d23af948c7dbbe5fe7339a9e1372fca12
zcat log/9929/logs-simtime-2/canton_network_test.clog.gz | grep -a -F "$CID" | grep -a -F 'ingestedCreatedEvents' | python3 -c 'import sys,json,re
cid=sys.argv[1]
for l in sys.stdin:
    j=json.loads(l); m=j["message"]
    for b in re.findall(r"CreatedEvent\(\s*contractId = (\S+),\s*templateId = ([^,\s]+)", m):
        if b[0].startswith(cid): print(j["@timestamp"], j["logger_name"][-30:], b[1][-30:])' $CID | sort -u
zcat log/9929/logs-simtime-2/canton_network_test.clog.gz | grep -a -F "$CID" | grep -a -F 'open-and-issuing-mining-rounds' \
  | grep -a -F 'Responding with entity data' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c12-120 | head -2
```
```
2026-09-02T14:15:05.672Z c22b58dc/SV=sv1/store=dsoParty plice.Round:IssuingMiningRound
2026-09-02T14:15:05.672Z t/config=c22b58dc/scan=sv1Scan plice.Round:IssuingMiningRound
14:15:10.756Z HTTP POST /api/scan/v0/open-and-issuing-mining-rounds from (127.0.0.1:43012): Responding with e
14:15:10.757Z HTTP POST /api/scan/v0/open-and-issuing-mining-rounds from (127.0.0.1:43026): Responding with e
```

## 4. It was closed by the round automation while the send was in flight

```
CID=00cb078b6ef81a2ab910c04cb2ca1a532a810d23af948c7dbbe5fe7339a9e1372fca12
zcat log/9929/logs-simtime-2/canton_network_test.clog.gz | grep -a -F "$CID" | grep -a -E 'T14:15:1(0\.946|2\.25[48])' | python3 -c '
import sys,json,re
for l in sys.stdin:
    j=json.loads(l); m=j["message"]
    ch=re.findall(r"choice = \"?([A-Za-z_]+)|\b(AmuletRules_[A-Za-z_]+|MiningRound_[A-Za-z_]+|DsoRules_[A-Za-z_]+)\b", m)
    print(j["@timestamp"][11:23], j.get("logger_name","")[:95], sorted({a or b for a,b in ch})[:6], re.sub(r"\s+"," ",m[:140]))
' | head -2
```
```
14:15:10.946 o.l.s.s.a.d.ExpireIssuingMiningRoundTrigger:WalletMintingDelegationTimeBasedIntegrationTest/con [] Processing ReadyTask( readyAt = 1970-01-02T04:02:34.999Z, work = AssignedContract( contract = Contract( contractId = 00cb078b6
14:15:12.254 o.l.s.a.a.c.ApiClientRequestLogger:WalletMintingDelegationTimeBasedIntegrationTest/config=c22b5 ['AmuletRules_MiningRound_Close', 'DsoRules_MiningRound_Close'] Request (tid:70f4935362e0fc5d25af592039598731) com.daml.ledger.api.v2.UpdateService/GetUpdates to 127.0.0.1:15101: received a message GetUpd
```

The send, from the validator's side:

```
zcat log/9929/logs-simtime-2/canton_network_test.clog.gz | grep -a 'T14:15:1[0-2]' \
  | grep -a -E 'transfer-preapproval/send|tid:771b597b.*(SubmitAndWaitForTransaction to .*: (sending|received)|failed)' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c12-160 | head -4
```
```
14:15:12.043Z HTTP POST /api/validator/v0/wallet/transfer-preapproval/send from (127.0.0.1:38358): received request.", [o.l.s.a.a.HttpRequestLogger:W
14:15:12.043Z HTTP POST /api/validator/v0/wallet/transfer-preapproval/send from (127.0.0.1:38358): Received entity data: {\"receiver_party_id\":\"cou
14:15:12.285Z Request (tid:771b597b7fd75b4b3975c3432d664e59) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15501: se
14:15:12.905Z Request (tid:771b597b7fd75b4b3975c3432d664e59) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15501: fa
```

Sequence: the 25 h jump makes the issuing round expirable (readyAt 04:02:34.999, 5 s before the new sim time
04:02:39.999); sv1's `ExpireIssuingMiningRoundTrigger` picks it up at 14:15:10.946; sv1Scan serves the round as issuing at 14:15:10.756; the send arrives at
14:15:12.043; the close (`DsoRules_MiningRound_Close` -> `AmuletRules_MiningRound_Close`)
is on the ledger by 14:15:12.254; the send's transaction is submitted at 14:15:12.285, still referencing the closed
round, and aliceParticipant rejects it at 14:15:12.905. This is the #7223 mechanism: one large jump queues round
advances and expiries under a command that uses the current round.

## 5. The fix is on main, but after this run

```
git merge-base --is-ancestor 0c43730f70 79e56f457f && echo PRESENT || echo MISSING
git log -1 --format='%h %ad %s' --date=iso 0c43730f70
git show 79e56f457f:nix/canton-sources.json | grep -m1 version
```
```
MISSING
0c43730f70 2026-09-16 17:10:19 +0900 Fix flake in WalletMintingDelegationTimeBasedIntegrationTest caused by time advancing too far (#7261)
  "version": "3.5.16-snapshot.20260901.19217.0.v1ed99f4c",
```

#7261 changes `mintDelay` to 45 min and replaces the single 25 h jump with `advanceRoundsUntil(mintAfter)` (see
the 10154 packet, section 3).

## Verdict

- Duplicate of 10060 / #7223 (family D sibling, catalogue entry "advanceTime(PT25H) then LOCAL_VERDICT_INACTIVE_CONTRACTS").
- Flake, test-side, already fixed on main by #7261 (0c43730f70, 2026-09-16); this main run (2026-09-02) predates it.
  No fix branch. Close 9929 as a duplicate of 10060.
- Not verified: that no main run after 2026-09-16 hits this signature (not searched); the Canton-side ordering of
  the close and the rejection (only the app-side log was read; canton-simtime.clog.gz not needed for the verdict).
