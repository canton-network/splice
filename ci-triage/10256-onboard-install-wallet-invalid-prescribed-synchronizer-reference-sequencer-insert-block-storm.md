# 10256 - TokenStandardCliTestDataTimeBasedIntegrationTest: onboarding bob's wallet user fails with INVALID_PRESCRIBED_SYNCHRONIZER_ID because global-domain ordering stalls 17.8 s on the reference sequencer's `insert block` 40001 retry storm (run 36707602651)

Family L (reference sequencer `insert block` SQLSTATE 40001 retry storm), third occurrence, first with this symptom.
main 825a1daf84 (the Canton upgrade to 3.6.0-snapshot.20260929, #7537), job `simtime (1)`, 14 of 15 tests passed. The
validator's onboarding allocates bob's party and waits until it is observed on the ledger API, which the splitwell
synchronizer satisfies at 11:40:15.766. The `WalletAppInstall` submission is prescribed to global-domain, where the
same party-to-participant transaction is only processed at 11:40:31.972: all four global reference sequencers retry
`insert block` on SQLSTATE 40001 with a backoff growing to 5.583 s, so no global-domain block is written from
11:40:14.175 to 11:40:31.946. `installWalletForUser` retries under `RetryFor.ClientCalls` (12 retries, 100 ms to 1 s)
and gives up at 11:40:23.399, 8.6 s before the party arrives.

- Run: https://github.com/canton-network/splice/actions/runs/36707602651, main 825a1daf84 ("Upgrade Canton to
  3.6.0-snapshot.20260929.20331.0.v07b3f95b (#7537)"), job 109861814030 `ci / scala_test_sim_time / simtime (1)`.
- Runtime canton: 3.6.0-snapshot.20260929.20331.0.v07b3f95b (`git show 825a1daf84:nix/canton-sources.json`).
- Component: test infra / Canton reference sequencer (contention); splice validator onboarding (retry budget).

## 1. Failing test

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10256/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info] *** 1 TEST FAILED ***
[info] - expire amulet transfer instructions handling both locked and already unlocked amulets *** FAILED ***
[info] Run completed in 17 minutes, 8 seconds.
[info] Tests: succeeded 14, failed 1, canceled 0, ignored 1, pending 0
```

## 2. Suite timeline

```
zcat log/10256/logs-simtime-1/canton_network_test.clog.gz | grep -a -E "TokenStandardCliTestData" | grep -a -E "Starting test suite|Test (succeeded|failed)|Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c12-200
```
```
11:39:03.581Z Starting test suite 'TokenStandardCliTestDataTimeBasedIntegrationTest'...",
11:39:26.574Z Starting 'TokenStandardCliTestDataTimeBasedIntegrationTest/Token Standard CLI should have up-to-date test data'...",
11:39:54.408Z Test succeeded: 'TokenStandardCliTestDataTimeBasedIntegrationTest/Token Standard CLI should have up-to-date test data'",
11:39:54.409Z Starting 'TokenStandardCliTestDataTimeBasedIntegrationTest/expire amulet allocations handling both locked and already unlocked amulets'...",
11:40:12.650Z Test succeeded: 'TokenStandardCliTestDataTimeBasedIntegrationTest/expire amulet allocations handling both locked and already unlocked amulets'",
11:40:12.650Z Starting 'TokenStandardCliTestDataTimeBasedIntegrationTest/expire amulet transfer instructions handling both locked and already unlocked amulets'...",
11:40:23.414Z Test failed: 'TokenStandardCliTestDataTimeBasedIntegrationTest/expire amulet transfer instructions handling both locked and already unlocked amulets', message: Command executi
```

## 3. The onboarding: party observed, then 12 rejected installs, then give-up

```
zcat log/10256/logs-simtime-1/canton_network_test.clog.gz | grep -a -E 'T11:40:(1[5-9]|2[0-3])' | grep -a -E 'bob_wallet_user-65c37ee5_tc2 on bobValidator|is observed on ledger API|Installing wallet for endUserName:bob|installWalletForUser.*(Retrying after a number of (0|11) failures|Giving up)' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | sed -E 's/\[([a-z]\.)+([A-Za-z$0-9]+)(:[^]]*)?\]/[\2]/' | grep -a -E 'bob|installWalletForUser' | cut -c12-200
zcat log/10256/logs-simtime-1/canton_network_test.clog.gz | grep -a -m1 'INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,07e30741)' | grep -oE 'INVALID_PRESCRIBED[^"]{0,170}' | sed -E 's/1220[0-9a-f]{8}\.\.\./../g' | head -1
```
```
11:40:15.524Z Running clue: Onboard bob_wallet_user-65c37ee5_tc2 on bobValidator", [TokenStandardCliTestDataTimeBasedIntegrationTest]
11:40:15.563Z Waiting until Party bob__wallet__user-65c37ee5__tc2::1220e69fcea4... is observed on ledger API", [SpliceLedgerConnection]
11:40:15.564Z The operation 'Check whether Party bob__wallet__user-65c37ee5__tc2::1220e69fcea4... is observed on ledger API' failed with a retryable error (full stack trace omitted): NOT_FO
11:40:15.564Z The operation 'Check whether Party bob__wallet__user-65c37ee5__tc2::1220e69fcea4... is observed on ledger API' has failed with an exception. New kind of error: transient error
11:40:15.764Z Now retrying operation 'Check whether Party bob__wallet__user-65c37ee5__tc2::1220e69fcea4... is observed on ledger API'. ", [SpliceLedgerConnection]
11:40:15.766Z Success: Party bob__wallet__user-65c37ee5__tc2::1220e69fcea4... is observed on ledger API", [SpliceLedgerConnection]
11:40:16.114Z Installing wallet for endUserName:bob_wallet_user-65c37ee5_tc2, endUserParty=bob__wallet__user-65c37ee5__tc2::1220e69fcea4..., validatorServiceParty=bob-validator65c37ee5-1::1
11:40:16.124Z The operation 'installWalletForUser' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a number of 0 failures, and af
11:40:22.592Z The operation 'installWalletForUser' has failed with an exception. Retrying after a number of 11 failures, and after 800 milliseconds. ", [HttpValidatorAdminHandler]
11:40:23.399Z The operation 'installWalletForUser' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. ", [HttpValidatorAdminHandler]
11:40:23.411Z Failed clue: Onboard bob_wallet_user-65c37ee5_tc2 on bobValidator", [TokenStandardCliTestDataTimeBasedIntegrationTest]
INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,07e30741): Not all informees are on the specified synchronizer: global-domain::..::36-0, but on Set(splitwell::..::35-0)\n  T
```

## 4. bob's participant: the party reaches splitwell at 15.659, global-domain only at 31.972

The three `Persisted` lines are bob's party-to-participant transaction in the authorized store, the splitwell store
and the global-domain store. Between 15.473 and 31.962 bob's participant receives no global-domain event at all; the
event at `...20.999442Z` (sequenced before splitwell's `...20.999446Z`) arrives 16.5 s late.

```
zcat log/10256/logs-simtime-1/canton-simtime.clog.gz | grep -a 'participant=bobParticipant' | grep -a -E 'T11:40:(1[5-9]|2[0-9]|3[0-2])' | grep -a -E 'Validating sequenced event coming from|Persisted topology transactions.*bob__wallet__user-65c37ee5__tc2' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\\n.*//; s/"logger_name.*//; s/1220[0-9a-f]{8}\.\.\.//g' | cut -c12-190
```
```
11:40:15.473Z Validating sequenced event coming from SEQ::sv1:: (alias = Sequencer 'sequencer-connection-DefaultSequencer-0') with timestamp 1970-01-02T09:25:20.999400Z",
11:40:15.559Z Persisted topology transactions (SequencedTime(1970-01-02T09:25:20.999002Z), EffectiveTime(1970-01-02T09:25:20.999002Z)):
11:40:15.649Z Validating sequenced event coming from SEQ::splitwellValidator:: (alias = Sequencer 'sequencer-connection-DefaultSequencer-0') with timestamp 1970-01-02T09:25:20.999
11:40:15.659Z Persisted topology transactions (SequencedTime(1970-01-02T09:25:20.999446Z), EffectiveTime(1970-01-02T09:25:20.999446Z)):
11:40:15.661Z Validating sequenced event coming from SEQ::splitwellValidator:: (alias = Sequencer 'sequencer-connection-DefaultSequencer-0') with timestamp 1970-01-02T09:25:20.999
11:40:31.962Z Validating sequenced event coming from SEQ::sv1:: (alias = Sequencer 'sequencer-connection-DefaultSequencer-0') with timestamp 1970-01-02T09:25:20.999442Z",
11:40:31.972Z Persisted topology transactions (SequencedTime(1970-01-02T09:25:20.999442Z), EffectiveTime(1970-01-02T09:25:20.999442Z)):
11:40:31.978Z Validating sequenced event coming from SEQ::sv1:: (alias = Sequencer 'sequencer-connection-DefaultSequencer-0') with timestamp 1970-01-02T09:25:21.002666Z",
```

## 5. The sequencer side: no global-domain block for 17.8 s, `insert block` backoff up to 5.583 s

Block processing on globalSequencerSv1 around the gap, then sv1's `insert block` retry chain, the SQLSTATE of the
first retries, and bob's send (received 15.563, validated in block 7804 at 31.951).

```
C=log/10256/logs-simtime-1/canton-simtime.clog.gz
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'Processing block [0-9]+, data chunk 0' | grep -a -E 'T11:40:(1[3-9]|2[0-9]|3[0-2])' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\\n.*//' | cut -c12-110 | sed -n '/block 770[2-5]/p;/block 780[3-5]/p'
zcat $C | grep -a -E 'T11:40:(1[3-9]|2[0-9]|3[0-2])' | grep -a 'sequencer=globalSequencerSv1' | grep -a "The operation 'insert block'" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//' | grep -oE '^[^ ]+|Retrying after [0-9.]+s' | paste - - | cut -c12-
zcat $C | grep -a -m2 -E 'T11:40:1[3-5].*SQL state: 40001' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name":"[^"]*sequencer=([a-zA-Z0-9]+)[^"]*".*/[\1]/' | cut -c12-120
zcat $C | grep -a -E 'ce406282-6d8e-478f-bd0c-0b1a66c70319' | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'sends request|validated to' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//; s/1220[0-9a-f]{8}\.\.\.//g' | cut -c12-190
```
```
11:40:13.695Z Processing block 7702, data chunk 0. Last chunk ts=1970-01-02T09:25:20.999396Z, last 
11:40:14.175Z Processing block 7705, data chunk 0. Last chunk ts=1970-01-02T09:25:20.999398Z, last 
11:40:31.946Z Processing block 7803, data chunk 0. Last chunk ts=1970-01-02T09:25:20.999400Z, last 
11:40:31.948Z Processing block 7804, data chunk 0. Last chunk ts=1970-01-02T09:25:20.999429Z, last 
11:40:32.066Z Processing block 7805, data chunk 0. Last chunk ts=1970-01-02T09:25:21.002666Z, last 
11:40:13.127Z	Retrying after 0.05s
11:40:13.189Z	Retrying after 0.061s
11:40:13.366Z	Retrying after 0.05s
11:40:13.418Z	Retrying after 0.077s
11:40:13.606Z	Retrying after 0.05s
11:40:13.852Z	Retrying after 0.057s
11:40:13.920Z	Retrying after 0.187s
11:40:14.113Z	Retrying after 0.21s
11:40:15.438Z	Retrying after 0.599s
11:40:16.085Z	Retrying after 1.329s
11:40:17.922Z	Retrying after 2.655s
11:40:21.120Z	Retrying after 5.225s
11:40:26.351Z	Retrying after 5.583s
11:40:31.987Z	Retrying after 0.05s
11:40:32.106Z	Retrying after 0.05s
11:40:13.096Z Detected an SQLException. SQL state: 40001, error code: 0",[globalSequencerSv2]
11:40:13.127Z Detected an SQLException. SQL state: 40001, error code: 0",[globalSequencerSv3]
11:40:15.563Z 'PAR::bobValidator::' sends request with id 'ce406282-6d8e-478f-bd0c-0b1a66c70319' of size 668 bytes with 1 envelopes.",
11:40:31.951Z At block 7804, the submission request ce406282-6d8e-478f-bd0c-0b1a66c70319 at 1970-01-02T09:25:20.999442Z validated to: Deliver",
```

## 6. The validator's retry budget and the canton pin

```
git show 825a1daf84:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryFor.scala | sed -n '70,74p'
git show 825a1daf84:apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/util/ValidatorUtil.scala | sed -n '107p'
git show 825a1daf84:nix/canton-sources.json | grep -m1 version
```
```
  val ClientCalls: RetryFor = RetryFor(
    maxRetries = 12,
    initialDelay = 100.millis,
    maxDelay = 1.seconds,
    resetRetriesAfter = None,
      retryFor: RetryFor = RetryFor.ClientCalls,
  "version": "3.6.0-snapshot.20260929.20331.0.v07b3f95b",
```

## Verdict

- Family L, third occurrence (after 10139 and 10197); flake from Postgres contention between the reference block
  sequencers, not the Canton upgrade in this commit: the same storm is documented on 3.5.x and 3.6.0-snapshot.20260925
  pins, here reaching a 5.583 s backoff (10197: 8.6 s).
- Symptom resembles 10227 (family B signature 3, same `INVALID_PRESCRIBED_SYNCHRONIZER_ID ... but on Set(splitwell...)`)
  but the cause differs: no BFT orderer is involved; the global synchronizer here uses the reference driver.
- Fix location: the contention is Canton / test infra (family L). Splice-side mitigation, described, not written:
  `ValidatorUtil.onboard` waits for the party on the ledger API, which any synchronizer satisfies, and then retries the
  install with the 7-8 s `RetryFor.ClientCalls` budget; waiting until the party is effective on the prescribed
  synchronizer (or retrying `INVALID_PRESCRIBED_SYNCHRONIZER_ID` under a longer budget) would ride out ordering stalls.
  That is production code (validator app), so it is left to the owner. No fix branch.
- Not verified: why the 40001 conflicts spike at 11:40:13 in this run; whether the Canton upgrade changes the reference
  driver's insert path (not diffed).
