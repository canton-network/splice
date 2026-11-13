# 11412 - simtime (0): splitwellValidator createSplitwellRules and installWalletForUser rejected after the 1 -> 4 switch, setup timeout, no namespace reset, 6 more suites fail (run 43444026219)

DUPLICATE of 11255 (rejected send of transactions, same as 11398). Main 87d584aa16, job simtime (0), Canton 3.7.3. In
the setup of TrafficBasedRewardsSvAppTimeBasedIntegrationTest (first suite of the shard) the global ordering topology
goes from 1 to 4 nodes at 14:48:48.210. sv1's sequencer rejects the splitwellValidator confirmation requests of
createSplitwellRules (14:48:49.088) and installWalletForUser (14:48:49.342) with `P2P connectivity is not ready
(authenticated = 1 < dissemination quorum = 2)`. Both carry max sequencing time 00:01:00 against a sim clock at
00:00:00.21, so they stay in flight: 69 `SUBMISSION_ALREADY_IN_FLIGHT` retries from splitwellValidator and 24 from
providerSplitwellBackend, then the 5 min `waitForInitialization` of splitwellValidator fails the setup at 14:54:17.
No namespace reset follows (setup-failure blast radius): WalletRewardsTimeBased fails its setup,
ScanWithGradualStartsTimeBased fails with `sv1Scan ... not accessible`, and 4 suites fail in `Creating fixture`.
Tests: succeeded 0, failed 3. Not caused by #8467 (the run's commit): same shape on c9c875cfc5 (11398) and
ee0c92e0fd (11406), both before it.

- Run: https://github.com/canton-network/splice/actions/runs/43444026219, main 87d584aa16 ("Deduplicate DAR uploads,
  allow 90s for sv4 in SvTimeBasedOnboarding IT (#8467)"), job 131949174300 `ci / scala_test_sim_time / simtime (0)`,
  attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at 87d584aa16).
- Component: Canton BFT ordering under simulated time.

## 1. Failing tests

```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info] *** 3 TESTS FAILED ***
[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info] - initialize a scan app that joins late *** FAILED ***
[info] - list and automatically collect app & validator rewards *** FAILED ***
[info] - should list and automatically collect app & validator rewards *** FAILED ***
[info] Run completed in 19 minutes, 35 seconds.
[info] Tests: succeeded 0, failed 3, canceled 0, ignored 0, pending 0
```

## 2. Suite timeline

The first suite's setup fails after the 5 min wait; no `has been reset` line in the whole log.
```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Running setup|Creating fixture" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170; echo "reset lines: $(zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -c 'has been reset')"
```
```
2026-11-13T14:45:04.071Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-13T14:45:07.308Z Starting creating environment: Creating fixture",
2026-11-13T14:45:09.460Z Finished creating environment: Creating fixture",
2026-11-13T14:45:50.582Z Starting creating environment: Running setup",
2026-11-13T14:54:17.487Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",
2026-11-13T14:54:17.812Z Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', mes
2026-11-13T14:54:17.897Z Starting test suite 'WalletRewardsTimeBasedIntegrationTest'...",
2026-11-13T14:54:18.235Z Starting creating environment: Creating fixture",
2026-11-13T14:54:18.331Z Finished creating environment: Creating fixture",
2026-11-13T14:54:31.696Z Starting creating environment: Running setup",
2026-11-13T14:59:31.774Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",
2026-11-13T14:59:31.836Z Test failed: 'WalletRewardsTimeBasedIntegrationTest/A wallet should list and automatically collect app & validator rewards', message: Creating en
2026-11-13T14:59:31.855Z Starting test suite 'ScanWithGradualStartsTimeBasedIntegrationTest'...",
2026-11-13T14:59:32.439Z Starting creating environment: Creating fixture",
2026-11-13T14:59:33.053Z Finished creating environment: Creating fixture",
2026-11-13T14:59:33.054Z Starting creating environment: Running setup",
2026-11-13T14:59:33.199Z Finished creating environment: Running setup",
2026-11-13T15:04:36.083Z Test failed: 'ScanWithGradualStartsTimeBasedIntegrationTest/initialize a scan app that joins late', message: The app state of sv1Scan is currentl
2026-11-13T15:04:36.109Z Starting test suite 'TokenStandardCliTestDataTimeBasedIntegrationTest'...",
2026-11-13T15:04:36.389Z Starting creating environment: Creating fixture",
2026-11-13T15:04:36.392Z Failed creating environment: Creating fixture (EnvironmentSetup.scala:146)",
2026-11-13T15:04:36.978Z Aborted test suite 'TokenStandardCliTestDataTimeBasedIntegrationTest'.",
2026-11-13T15:04:36.992Z Starting test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'...",
2026-11-13T15:04:37.267Z Starting creating environment: Creating fixture",
2026-11-13T15:04:37.268Z Failed creating environment: Creating fixture (EnvironmentSetup.scala:146)",
2026-11-13T15:04:37.269Z Aborted test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'.",
2026-11-13T15:04:37.279Z Starting test suite 'FeaturedAppRightSwitchOverTimeBasedIntegrationTest'...",
2026-11-13T15:04:37.549Z Starting creating environment: Creating fixture",
2026-11-13T15:04:37.551Z Failed creating environment: Creating fixture (EnvironmentSetup.scala:146)",
2026-11-13T15:04:37.552Z Aborted test suite 'FeaturedAppRightSwitchOverTimeBasedIntegrationTest'.",
2026-11-13T15:04:37.561Z Starting test suite 'WalletAmuletPriceTimeBasedIntegrationTest'...",
2026-11-13T15:04:37.812Z Starting creating environment: Creating fixture",
2026-11-13T15:04:37.814Z Failed creating environment: Creating fixture (EnvironmentSetup.scala:146)",
2026-11-13T15:04:37.815Z Aborted test suite 'WalletAmuletPriceTimeBasedIntegrationTest'.",
reset lines: 0
```

Cause of the first setup failure:
```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -m1 'Failed creating environment: Running setup' | grep -a -oE '"stack_trace":"[^"]{0,400}' | sed 's/\\n\\t/\n  /g; s/\\n/\n/g' | head -4
```
```
"stack_trace":"java.lang.IllegalStateException: Condition never became true within 5 minutes
  at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
  at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
  at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceR
```

## 3. Ordering topology 1 -> 4 at 14:48:48.210, no blacklisting

```
zcat logs-simtime-0/canton-simtime.clog.gz | grep -a -E 'New epoch [0-9]+ has started' | grep -a 'T14:4[5-9]' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/leaders = .*(and blacklisted nodes = List\([^)]*\)).*size = ([0-9]+).*/\1 size=\2/; s/"logger_name":"[^:]*:sequencer=([^/"]*).*/ \1/' | awk '{if(!($NF in s)){s[$NF]=1; print}}' | cut -c1-160
```
```
2026-11-13T14:45:01.720Z New epoch 38 has started with and blacklisted nodes = List() size=1
2026-11-13T14:48:48.210Z New epoch 14 has started with and blacklisted nodes = List() size=4
```

```
zcat logs-simtime-0/canton-simtime.clog.gz | awk '/P2P connectivity is not ready/{a++} /Failed to send result/{b++} /currently blacklisted/{c++} END{print "p2p-not-ready="a+0, "failed-send-result="b+0, "currently-blacklisted="c+0}'
```
```
p2p-not-ready=35 failed-send-result=0 currently-blacklisted=0
```

## 4. Rejected submissions

The two `P2P connectivity is not ready` rejections are both of the splitwellValidator participant, 0.88 and 1.13 s
after the switch. The MED::sv4 and MED::sv2 rejections at 14:49:00-07 are aggregation duplicates (`already marked as
delivered`), not connectivity rejections.
```
zcat logs-simtime-0/canton-simtime.clog.gz | grep -a 'Rejecting submission request by' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+\.\.\./::../; s/ with SequencerDeliverError.*message = ([^)]*\)).*sequencer=([^/"]*).*/ | \1 | \2/' | cut -c1-200
```
```
2026-11-13T14:48:49.088Z Rejecting submission request by Some(PAR::splitwellValidator::..) | P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2) | globalSequencerSv1
2026-11-13T14:48:49.343Z Rejecting submission request by Some(PAR::splitwellValidator::..) | P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2) | globalSequencerSv1
2026-11-13T14:49:00.244Z Rejecting submission request by Some(MED::sv4::..) | Not accepting submission as aggregation ID SHA-256:55f37aa9c6c7... is already marked as delivered) | globalSequencerSv4
2026-11-13T14:49:07.655Z Rejecting submission request by Some(MED::sv2::..) | Not accepting submission as aggregation ID SHA-256:290c24fd3b59... is already marked as delivered) | globalSequencerSv2
2026-11-13T14:49:07.661Z Rejecting submission request by Some(MED::sv2::..) | Not accepting submission as aggregation ID SHA-256:26f73717b373... is already marked as delivered) | globalSequencerSv2
2026-11-13T14:49:07.684Z Rejecting submission request by Some(MED::sv2::..) | Not accepting submission as aggregation ID SHA-256:cc86605a1a89... is already marked as delivered) | globalSequencerSv2
2026-11-13T14:49:07.730Z Rejecting submission request by Some(MED::sv2::..) | Not accepting submission as aggregation ID SHA-256:4662475d0a04... is already marked as delivered) | globalSequencerSv2
2026-11-13T14:49:07.772Z Rejecting submission request by Some(MED::sv2::..) | Not accepting submission as aggregation ID SHA-256:5700bf5c8fbc... is already marked as delivered) | globalSequencerSv2
```

## 5. What the two splitwellValidator sends were

Both carry max sequencing time 00:01:00, while
the sequencer estimates sequencing time 00:00:00.210.
```
zcat logs-simtime-0/canton-simtime.clog.gz | grep -a -E '09fe902f-c454|32933ad3-c72e' | grep -a -E 'Request to send submission|Estimated sequencing time' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+\.\.\./::../g; s/ from PAR.*//; s/","logger_name".*//' | cut -c1-170
```
```
2026-11-13T14:48:49.077Z Request to send submission with id 09fe902f-c454-456a-8ff0-92924e9e9d16 with max sequencing time 1970-01-01T00:01:00Z
2026-11-13T14:48:49.077Z Estimated sequencing time 1970-01-01T00:00:00.210Z for submission with id 09fe902f-c454-456a-8ff0-92924e9e9d16
2026-11-13T14:48:49.340Z Request to send submission with id 32933ad3-c72e-448e-8feb-4d2c9cf796a6 with max sequencing time 1970-01-01T00:01:00Z
2026-11-13T14:48:49.340Z Estimated sequencing time 1970-01-01T00:00:00.210Z for submission with id 32933ad3-c72e-448e-8feb-4d2c9cf796a6
```

The sequencer error carries the submitting trace id (`SEQUENCER_UNAVAILABLE(1,<tid prefix>)`):
```
zcat logs-simtime-0/canton-simtime.clog.gz | grep -a -E 'SendAsync by .*SEQUENCER_UNAVAILABLE' | grep -a 'T14:48:49' | grep -a -oE '^\{"@timestamp":"[^"]+|SEQUENCER_UNAVAILABLE\([0-9],[0-9a-f]+\)' | paste - - | sed 's/{"@timestamp":"//'
```
```
2026-11-13T14:48:49.089Z	SEQUENCER_UNAVAILABLE(1,b5f27720)
2026-11-13T14:48:49.343Z	SEQUENCER_UNAVAILABLE(1,a88bb20c)
```

Those trace ids in the app log: b5f27720 is createSplitwellRules for splitwell_provider, a88bb20c installWalletForUser
for splitwell_validator_user; both time out client-side after 120 s.
```
for t in b5f27720 a88bb20c; do zcat logs-simtime-0/canton_network_test.clog.gz | grep -a "tid:$t" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | awk -v t=$t '{c=$0; m=""; if(match(c,/org\.lfdecentralizedtrust\.splice\.[a-zA-Z.]+_/)) m=substr(c,RSTART,RLENGTH-1); s=(index(c,"sending request")?"sending request":(index(c,"failed with")?substr(c,index(c,"failed with"),60):"")); if(s!="") print substr(c,1,24), t, s, m}'; done
```
```
2026-11-13T14:48:48.395Z b5f27720 sending request org.lfdecentralizedtrust.splice.splitwell.createSplitwellRules
2026-11-13T14:50:48.395Z b5f27720 failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded  
2026-11-13T14:48:49.294Z a88bb20c sending request org.lfdecentralizedtrust.splice.validator.installWalletForUser
2026-11-13T14:50:49.294Z a88bb20c failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded  
```

## 6. In-flight retries until the setup timeout

Per app, then the first and the last line that mentions it (the last is splitwellValidator's init failure).

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'SUBMISSION_ALREADY_IN_FLIGHT' | grep -a TrafficBasedRewards | grep -a -oE '(validator|splitwell)=[A-Za-z0-9]+' | awk '{c[$0]++} END{for(k in c) print c[k], k}'; zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'SUBMISSION_ALREADY_IN_FLIGHT' | grep -a TrafficBasedRewards | sed -E 's/\{"@timestamp":"([^"]+)".*/\1/' | sed -n '1p;$p'
```
```
24 splitwell=providerSplitwellBackend
69 validator=splitwellValidator
2026-11-13T14:50:48.660Z
2026-11-13T14:54:17.578Z
```

## Verdict

Duplicate of 11255, rejected send of transactions under simulated time (the 11398 variant): at the 1 -> 4 step the
splitwellValidator confirmation requests for createSplitwellRules and installWalletForUser are rejected with `P2P
connectivity is not ready` 0.88 and 1.13 s after the switch and count as possibly sequenced until a max sequencing
time the sim clock never reaches, so every retry gets `SUBMISSION_ALREADY_IN_FLIGHT` until the 5 min init wait. Zero
`Failed to send result`, zero `currently blacklisted`, `blacklisted nodes = List()`. The setup failure skips the
namespace reset, which fails every later suite of the shard. Flake in Canton BFT ordering; no splice change beyond
what 11255 tracks.

Not verified: why sv1's sequencer still counts only one authenticated peer 1.1 s after the switch (Canton side, as
for every 11255 rejected-send hit).
