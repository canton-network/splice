# g4-bootstrap: 11360

Packet: `evidence/11360-bootstrap-package-config-close-vote-sv1-sequencer-blacklisted.md`.
Artifact: `log/11360/logs-resource-intensive-0` (run 43030349815, job 130555891102, attempt 1).

## Verdict

Duplicate of 11313 (third main hit after 11329), flake, test budget; no new family. Same check
(BootstrapPackageConfigIntegrationTest.scala:291, `observe AmuletRules with upgraded config`, 45 s at line 274) and
same onset as 11329: 192 vetting-trigger DAR uploads from 14:01:21 (the second the votes finished), canton.clog rate
10,300 to 134 lines/s by 14:01:23; ordering topology size 4, activation 13:59:07 unchanged until after the failure.
New detail: all four SV participants submit through globalSequencerSv1; its epoch 51 segment timed out (14:01:53.674)
and sv1 was blacklisted in epochs 52-54 (14:02:04.826 to 14:02:34.090). The confirmations of the first close
attempts, sent to sv1 at 14:01:55-56, were ordered only at 14:02:34.165, so the mediators rejected the close with
MEDIATOR_SAYS_TX_TIMED_OUT at 14:02:08; retries were refused (SEQUENCER_OVERLOADED, node blacklisted, surfaced as
SEQUENCER_BACKPRESSURE) until sv1 returned; sv2's retry succeeded at 14:02:36.039, 29.6 s after the check failed
(14:02:06.481) and 74.6 s after the votes. The run sha cannot contain the fix: PR splice#8412 (90 s) is
still open, not merged.

Frequency: resource-intensive (0) on main post-merge runs since 2026-11-06: 3 failures in 55 runs, all three this
check (11313, 11329, 11360). Rank: frequent flake (about one a day since 11-09), fix already open.

Fix: merge splice#8412 (`actAndCheck(timeUntilSuccess = 90.seconds)` at BootstrapPackageConfigIntegrationTest.scala:274).
Margin is thin: 74.6 s here, about 73 s in 11329, so a longer blacklist would exceed 90 s. Worth a hint on the PR
(owner's call): reducing the burst itself (sv2 trigger is already paused; the uploads of every intermediate DAR) or
more budget.

## INDEX.tsv row

```
11360	2026-11-11	43030349815	main 2fc780a883 (#8417)	130555891102 resource-intensive (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	BootstrapPackageConfigIntegrationTest:291 check failed 14:02:06.481; 192 vetting DAR uploads from 14:01:21, canton.clog 10300 to 134 lines/s at 14:01:23; sv1 sequencer (all SV participant submissions) blacklisted epochs 52-54 14:02:04.826-14:02:34.090; close confirmations sent 14:01:55-56 ordered 14:02:34.165, mediators MEDIATOR_SAYS_TX_TIMED_OUT 14:02:08, retries SEQUENCER_BACKPRESSURE, close committed 14:02:36.039 (74.6 s after votes); topology size 4 unchanged; 3 of 55 ri(0) main runs since 11-06	11313		close as dup of 11313	evidence/11360-bootstrap-package-config-close-vote-sv1-sequencer-blacklisted.md
```

## known-families.md (append to section L, after the 11329 bullet)

```
- 2026-11-11 (11360, resource-intensive (0), main 2fc780a883, Canton 3.7.3-snapshot.20261109): dup of 11313, third
  main hit. Same onset (192 DAR uploads from 14:01:21, canton.clog 10300 to 134 lines/s at 14:01:23), topology
  unchanged. Variant: the blacklisted node was sv1, whose sequencer carries every SV participant submission, so the
  close confirmations sat 38 s in its mempool and the mediators rejected the first close with
  MEDIATOR_SAYS_TX_TIMED_OUT; retries got SEQUENCER_BACKPRESSURE (SEQUENCER_OVERLOADED, node blacklisted) until sv1
  returned. Close committed 74.6 s after the votes (11329: about 73 s), inside the 90 s of splice#8412 (still open)
  but with little margin. Confirm: `MEDIATOR_SAYS_TX_TIMED_OUT` or late `succeeded(OK)` on DsoRules_CloseVoteRequest
  after `Failed clue: (check) observe AmuletRules with upgraded config`, plus the 11329 confirm. 3 of 55
  resource-intensive (0) main runs since 11-06, all this check.
```

Index table row L: add 11329, 11360 to "Parent / main refs" if the main thread keeps that list current.

## Lesson

Lesson: none (plain duplicate; the budget lesson, if any, belongs to 11313 when splice#8412 merges).

## Suggested action

Close as duplicate of 11313 (single-line plain-text comment, inline; the same text is in `comments/11360.md`):

```
direnv exec . ci-triage/scripts/close_duplicates.py 11313 11360 --comment 'Same check at BootstrapPackageConfigIntegrationTest.scala:291 after the vetting DAR upload burst: the close vote confirmations waited 38 s in the blacklisted sv1 sequencer, the mediators timed out the first close, and the close committed 30 s after the 45 s check, 75 s after the votes, inside the 90 s of the open fix PR.'
```

No recurrence comment on 11313: it has an assignee (stephencompall-DA) and an owner discussion, and its fix PR is open.
Assignee: none new; 11313 is already assigned to stephencompall-DA, author of splice#8412 (evidence: PR author line in
packet section 2).
