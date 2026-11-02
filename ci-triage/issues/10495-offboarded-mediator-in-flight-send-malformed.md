# 10495 - offboarded sv4 mediator's in-flight send rejected as malformed during standalone Canton shutdown (run 39963664449)

New occurrence, test-side ignore gap. Main 0c8c834609 (#7839), job `resource-intensive (0)`, canton
3.7.0-snapshot.20261022.20797.0.vd0a4e6f2. Tests: succeeded 3, failed 0. checkErrors flagged 4 WARNs in
`log/canton-standalone-mediator-offboarding_after_shutdown.clog`, one per standalone sequencer, for one submission
from `MED::sv4SvOffboarding` that was sent before the mediator group change and sequenced after it.

- Run: https://github.com/canton-network/splice/actions/runs/39963664449, main 0c8c834609 (#7839), job 120448347997
  `resource-intensive (0)`.
- Runtime canton: 3.7.0-snapshot.20261022.20797.0.vd0a4e6f2.
- Component: test (log ignore file of SvOffboardingIntegrationTest's standalone Canton).

## 1. Flagged lines

```
gh api repos/canton-network/splice/actions/jobs/120448347997/logs > log/10495/job.log
sed -n 14946,14951p log/10495/job.log | cut -c1-330
```
```
Found problems in log/canton-standalone-mediator-offboarding_after_shutdown.clog:
***"@timestamp":"2026-10-23T14:06:50.706Z","message":"Rejecting submission request e2bd60f1-85f4-4d6b-82ed-887ef19e5e79 from MED::sv4SvOffboarding::1220936d6bd0... with error SEQUENCER_SUBMISSION_REQUEST_MALFORMED_REJECTED(5,0): Validation of aggregation rule due to Sender [MED::sv4SvOffboarding::1220936d6bd0...] is not part of the mediator group with index 0","logger_name":"c.d.c.s.b.u.InFlightAggregationHandler:sequencer=sv2StandaloneSequencer/...
(same for sv1, sv4, sv3 StandaloneSequencer)
Total: 4 lines with problems.
```

## 2. Timeline: sent before the group change, sequenced after it, logged during shutdown

```
unzip -p log/10495/a.zip canton-standalone-mediator-offboarding_before_shutdown.clog.gz | gunzip -c > log/10495/before.clog
grep -a "e2bd60f1" log/10495/before.clog | grep -a "mediator=sv4" | head -3 | cut -c1-200
grep -a "Persisted topology transactions" log/10495/before.clog | grep -a MediatorSynchronizerState | grep -a mediator=sv4 | cut -c1-200
unzip -p log/10495/a.zip canton-standalone-mediator-offboarding_after_shutdown.clog.gz | gunzip -c | head -1 | cut -c1-120
```
```
2026-10-23T14:06:43.945Z Sending message ID e2bd60f1-85f4-4d6b-82ed-887ef19e5e79 to sequencer SEQ::sv4SvOffboarding::1220f322ac28...
2026-10-23T14:06:48.037Z Persisted topology transactions (SequencedTime(2026-10-23T14:06:46.931854Z), EffectiveTime(2026-10-23T14:06:47.181854Z)): ... MediatorSynchronizerState ...
{"@timestamp":"2026-10-23T14:06:50.176Z","message":"Shutting down...", ...
```
The submission was sequenced at 2026-10-23T14:06:49.619025Z ("At block 950, the submission request e2bd60f1... at
2026-10-23T14:06:49.619025Z validated to: Reject(...)"), after the MediatorSynchronizerState without sv4's mediator
became effective (14:06:47.18), and the WARN was written at 14:06:50.706, after Canton started shutting down, so it
lands in the after-shutdown log. The before-shutdown log has no such line (`grep -c "is not part of the mediator
group" before.clog` = 0).

## 3. The reonboarding tests ignore exactly this line; the offboarding ignore file does not

```
git grep -n "is not part of the mediator group" cn/main -- project/ignore-patterns/
git show cn/main:project/ignore-patterns/canton-standalone-mediator-offboarding.ignore.txt | grep -n "MED::sv4"
```
```
project/ignore-patterns/canton-standalone-sv123-reonboarding.ignore.txt:26:SEQUENCER_SUBMISSION_REQUEST_MALFORMED.*MED::sv4SvReonboarding.*(is not part of the mediator group|Validation of aggregation rule failed)
project/ignore-patterns/canton-standalone-sv4-reonboarding.ignore.txt:28:SEQUENCER_SUBMISSION_REQUEST_MALFORMED.*MED::sv4SvReonboarding.*(is not part of the mediator group|Validation of aggregation rule failed)
3:SEQUENCER_SENDER_UNKNOWN.*MED::sv4
14:Failed to send result to sequencer for request.*Unregistered recipients.*unregistered senders.*MED::sv4SvOffboarding
16:Member MED::sv4SvOffboarding.* access is disabled
```

## Verdict

New occurrence, flake by timing (needs a send from the mediator that is about to be removed, ordered after the
group change). The behaviour is the intended result of offboarding the mediator, not a defect: the sequencers reject a
submission from a sender that is no longer in the group. Fix (test-side): add the reonboarding pattern for
`MED::sv4SvOffboarding` to `project/ignore-patterns/canton-standalone-mediator-offboarding.ignore.txt`, which covers
both the before- and after-shutdown checks (build.sbt checkErrors). Not written as a branch here. Not verified: what
the message was (an aggregated mediator send; no payload logged).
