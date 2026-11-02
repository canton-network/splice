# 8784 - TokenStandardAllocation / TrafficBasedRewards: settlement venue submits before its participant has the allocations; PR #6013's wait never matched (run 27544243403)

- Original run: https://github.com/canton-network/splice/actions/runs/27544243403, main 21ed11e124, 2026-06-15; failed
  jobs simtime (3), signatures (0), roll-forward-lsu (1). Job logs and artifacts are gone (HTTP 410 / expired), so the
  original evidence is PR #6013's description: "The command submission on the splitwell participant fails with
  CONTRACT_NOT_FOUND" in TokenStandardAllocationIntegrationTest and TrafficBasedRewardsTimeBasedIntegrationTest.
  Which of the three jobs carried ref 8784 cannot be re-derived here.
- PR #6013 "Wait for splitwell to see allocations in test" (opened 2026-06-17, approved twice) is still OPEN,
  so it never fixed anything on main. Its own CI (run 29411909403, 2026-07-15) is red on 4 shards; that run is
  still available and is what this packet analyses.

## 1. What the PR added

```
gh pr diff 6013 --repo canton-network/splice | grep '^+' | grep -v '^+++'
```
Before the venue's settlement in both tests:
```
splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
  .awaitJava(amuletallocationCodegen.AmuletAllocation.COMPANION)(venueParty, predicate = c => c.id == aliceAllocationId)
```
`c.id` is an `AmuletAllocation.ContractId`; `aliceAllocationId` is an `allocationv1.Allocation.ContractId`
(`AllocatedOtcTrade`, `TokenStandardAllocationIntegrationTest.scala:417-420`).

## 2. Why the PR's CI failed: the wait can never succeed

```
gh api repos/canton-network/splice/actions/jobs/87340925910/logs | sed -E 's/\x1b\[[0-9;]*m//g' | grep -a -E 'FAILED \*\*\*|Tests: succeeded'
zcat log/8784/pr/logs-wall-clock-time-7/canton_network_test.clog.gz | grep -a TokenStandardAllocationIntegrationTest | grep -a -E 'Failed clue|Test failed'
```
```
- Cancel a DvP and its allocations *** FAILED ***     - Reject an allocation request *** FAILED ***
- Settle a DvP using allocations *** FAILED ***       - Withdraw an allocation *** FAILED ***
11:42:03.684Z Running clue: Wait for allocation ContractId(00934f45d06e...) to be ingested by splitwell participant
11:44:03.706Z Failed clue: Wait for allocation ContractId(00934f45d06e...) to be ingested by splitwell participant   (2 min timeout, all 4 tests)
```
The venue participant had the contract 0.8 s before the wait even started:
```
zcat log/8784/pr/logs-wall-clock-time-7/canton.clog.gz | grep -a 00934f45d06ea132a2da515db4d5ee0fcf2037bb8070c784f84767744570b7b464 | grep -a -oE '"@timestamp":"[^"]+"|participant=[A-Za-z0-9]+' | paste - - | sort | awk '!seen[$2]++'
```
```
2026-07-15T11:42:02.914Z participant=splitwellParticipant
2026-07-15T11:42:02.920Z participant=sv1Participant
2026-07-15T11:42:02.921Z participant=aliceParticipant
```
The predicate is the problem. `com.daml.ledger.javaapi.data.codegen.ContractId.equals` (bindings-java 3.5.15,
javap) checks `getClass().isAssignableFrom(other.getClass())` before comparing the id string; the two codegen
ContractId classes are unrelated siblings, so `c.id == aliceAllocationId` is always false and `awaitJava` polls
until its timeout. The same PR run's simtime (2)/(3) failures are the identical wait in the TBAR test; wall-clock (3)
is an unrelated WalletIntegrationTest CommandFailure.

## 3. State on main today

```
grep -c -E 'ingested by splitwell|awaitJava' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TokenStandardAllocationIntegrationTest.scala apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsTimeBasedIntegrationTest.scala
```
```
0 / 0
```
Both tests still wait only for SV1's scan to know the allocations (`getAllocationCancelContext`) and then submit
`OTCTrade_Settle` from the venue's participant with the two allocation contract ids as inputs. The venue is an
observer of `AmuletAllocation` (`AmuletAllocation.daml:53`, executors of the settlement), so its participant
receives the contracts, but nothing guarantees it has ingested them before the submission. The race PR #6013
described is therefore still open on main; whether it still fires cannot be told from here (no tracker access,
and none of the recent artifacts in this triage contain a splitwell-side CONTRACT_NOT_FOUND).

## 4. Fix

Branch `ray/fix-venue-allocation-wait` (47c1b9f7e3, off main): `TokenStandardTest.waitForAllocationsOnParticipant`
awaits each allocation on the venue's participant with `c.id.contractId == allocationId.contractId` (string
comparison across the two codegen id types), called from both tests right after the existing SV1 wait. Same idea
as #6013, with the equality bug removed. scalafmt clean; not compiled or run here. #6013 can be closed in favour of
it, or rebased with the predicate fixed.
