# 10731 - TokenStandardV2TransferIntegrationTest "buy traffic through a transfer to the traffic purchase receiver": races the validator's own top-up

Verdict: new family (P), test race introduced by #8012 (Simon Meier, merged 2026-10-27), one hit so far. The test
measures the DSO's total purchased traffic for aliceValidator's participant before and after its own purchase and
expects the difference to be exactly that purchase. aliceValidator's `TopupMemberTrafficTrigger` bought its regular
6,000,000 top-up for the same member in the same second, so the difference was 8,000,000 instead of 2,000,000.

- Run: https://github.com/canton-network/splice/actions/runs/40668702825 (CI post-merge, main 02f4b1a11e, #8018), job
  122750220926 `wall-clock-time (1)`.
- Test: `TokenStandardV2TransferIntegrationTest.scala:597` at 8585c2a759.

## 1. Failure

```
[info] - buy traffic through a transfer to the traffic purchase receiver *** FAILED ***
[info]   Check The DSO sees the purchased traffic for Alice buys traffic via a V2 transfer to the traffic purchase receiver 8000000 was not equal to 2000000 (TokenStandardV2TransferIntegrationTest.scala:597)
```

## 2. The concurrent top-up

```
gh api repos/canton-network/splice/actions/artifacts/12810023439/zip > wc1.zip   # logs-wall-clock-time-1
zcat canton_network_test.clog.gz | awk '/"@timestamp":"2026-10-28T10:50:(1[5-9]|[2-4][0-9])/' \
  | grep -a -i 'topup\|BuyMemberTraffic\|extra traffic limit\|Alice buys traffic' | grep -av DEBUG
```
```
10:50:22.666Z Processing Task(topupParameters = ExtraTrafficTopupParameters(topupAmount = 6000000, minTopupInterval = 60000 milliseconds), ...  [aliceValidator top-up trigger]
10:50:22.920Z Running clue: (act) Alice buys traffic via a V2 transfer to the traffic purchase receiver
10:50:23.414Z Completed processing with outcome: Successfully bought extra traffic: COO_BuyMemberTraffic(...)
10:50:23.840Z Finished clue: (act) Alice buys traffic via a V2 transfer to the traffic purchase receiver
10:50:24.130Z Updated extra traffic limit for member PAR::aliceValidator::122030e4beea... from 6000000 to 8000000
```

## Fix direction

In the test, pause `TopupMemberTrafficTrigger` on aliceValidator for this case (as WalletNoDevNetIntegrationTest does),
or assert on the MemberTraffic contract created by this transfer instead of the member's total.
