# 11164 - WalletSurviveCantonRestartIntegrationTest: onboarding times out (HTTP 503) while the global BFT sequencer of sv1 rejects submissions as blacklisted (run 41637827972)

new, rare. main 5c53b74294, job 125924130575 `wall-clock-time (0)`. The first user onboarding on the standalone
participant (before any restart) took longer than the 38 s HTTP request timeout: its `createValidatorRight` submission was
retried against `SEQUENCER_OVERLOADED ... this node is currently blacklisted` from globalSequencerSv1 from 13:08:56.

- Run: https://github.com/canton-network/splice/actions/runs/41637827972, main 5c53b74294, job 125924130575 `wall-clock-time (0)`, attempt 1.
- Component: Canton BFT sequencer (shared test Canton), test.

## 1. Failure

```
zcat log/11164/art/canton_network_test.clog.gz | grep -a 'WalletSurviveCantonRestart' | grep -aE 'HTTP 503|Failed clue: Onboard' | cut -c1-260
```
```
13:09:16.904 HttpCommandException: HTTP 503 Service Unavailable POST at '/api/validator/v0/admin/users' on 127.0.0.1:5503. Command failed, message: The server is taking too long to respond to the (POST) request ...
13:09:16.904 Failed clue: Onboard alice_wallet_user-6653190 on aliceValidatorLocal
```
Request received 13:08:38.883.

## 2. What the onboarding waited on (aliceValidatorLocal)

```
zcat log/11164/art/canton_network_test.clog.gz | grep -a aliceValidatorLocal | grep -aE 'createValidatorRight|SEQUENCER_BACKPRESSURE' | head -4
```
```
13:08:56.819 SubmitAndWait to 127.0.0.1:27501: failed with ABORTED/SEQUENCER_BACKPRESSURE(2,b6fc87b9): The sequencer is overloaded.
13:08:56.820 The operation 'createValidatorRight' failed with a retryable error ... SEQUENCER_BACKPRESSURE
13:08:57.415 SubmitAndWait ... failed with ABORTED/SEQUENCER_BACKPRESSURE(2,31212583)
```
Before that, the install submission took from 13:08:41 to 13:08:56.548.

## 3. Sequencer side

```
zcat log/11164/art/canton.clog.gz | grep -a 'currently blacklisted, rejecting' | grep -a 'Rejecting submission request (' | (timestamps per minute and sequencer)
```
```
     40 12:53 sequencer=globalSequencerSv3
     13 13:08 sequencer=globalSequencerSv1
    109 13:09 sequencer=globalSequencerSv1
```
```
13:08:56.651 Rejecting submission request by Some(PAR::aliceValidatorLocal::12202da29340...) with SequencerDeliverError(code = SEQUENCER_OVERLOADED, message = Mempool received client request but this node is currently blacklisted, rejecting)
```
Log volume of the shared Canton dropped from thousands of lines per second to 107-178 in several seconds between
13:08:43 and 13:08:53 (canton.clog per-second counts), so the Canton process was slow just before sv1 got blacklisted.

## 4. History

Earlier WalletSurviveCantonRestartIntegrationTest issues (5366, 5417, 6379, 6394; 2025) were participant init races,
all closed. Family AA (11104) is the blacklisting of a late-joining LSU successor sequencer, a different trigger.

## Verdict
One-off: the shared global BFT sequencer of sv1 was blacklisted by its peers for at least 40 s while the test onboarded
its first user; the onboarding HTTP call timed out on the backpressure retries. Not verified: why sv1 fell behind (the
Canton log slows down at 13:08:43-53; cause not in the artifacts).
