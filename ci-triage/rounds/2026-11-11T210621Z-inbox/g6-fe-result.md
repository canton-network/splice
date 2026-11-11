# g6-fe result: 11361

Packet: `ci-triage/evidence/11361-ans-search-button-stale-dup-11187.md`

## Verdict

11361: DUPLICATE of 11187 (family F). AnsFrontendIntegrationTest `allow requesting an entry with subscription payments
and then list it`, clue `requesting an existing name to check the already taken message`: `StaleElementReferenceException`
on `search-entry-button` at `eventuallyClickOn` (FrontendIntegrationTest.scala:866) from AnsFrontendIntegrationTest.scala:51,
same test, clue, element and frame as 11187 and 11300.

Mechanism (at 644b3ba97a): the test types the name, then waits for the search button to be clickable (finished
14:10:39.011) and clicks it (failed 14:10:39.154). The name is applied through a 500 ms debounce
(RequestAnsEntry.tsx), and the debounced lookup fired in between (validator received the by-name lookup at
14:10:39.152). The button sits in `DisableConditionally`, which renders a different tree while loading
(`<Stack><Tooltip><span>`), so React remounted it and the reference went stale. The screenshot at 14:10:39.244 shows
"mycool_entry.unverified.ans is not available": the app behaved correctly.

Frequency: 3 of 98 `frontend-wall-clock-time (0)` jobs on main post-merge runs since 2026-11-04 (96 runs) failed, all
three this race (11187, 11300, 11361): about once every 2 to 3 days.

Fix: canton-network/splice#8418 (stephencompall-DA, the 11187 assignee), open, not merged: wraps click plus
`waitForQuery(unavailable-icon, 5 s)` in `eventually()` for the three search steps of AnsFrontendIntegrationTest.
Its frontend shards passed; it is blocked by a failed `resource-intensive (0)` on its run (not inspected, unrelated
shard) and has no review yet. No new fix needed; merging #8418 closes the family member.

Rank: frequent-ish flake (3 per week on one shard, every 2 to 3 days), fix already open; low urgency for triage.

## INDEX.tsv row

```
11361	2026-11-11	43031077112	main 644b3ba97a (#8424)	130559083969 frontend-wall-clock-time (0)	3.7.3-snapshot.20261109.20894.0.ve6e6c252	AnsFrontendIntegrationTest already-taken clue: search-entry-button clickable 14:10:39.011, debounced name lookup at validator 14:10:39.152, StaleElementReferenceException 14:10:39.154; 3 of 98 frontend-wall-clock-time (0) jobs on main since 11-04, all this race	11187 (F)	test flake	close as dup of 11187; fix splice#8418 open (not merged)	evidence/11361-ans-search-button-stale-dup-11187.md
```

## known-families.md text (family F, new bullet)

```
- ANS search button remounted between check and click: `StaleElementReferenceException` on `search-entry-button` in
  AnsFrontendIntegrationTest (already-taken clue, AnsFrontendIntegrationTest.scala:51 via eventuallyClickOn
  FrontendIntegrationTest.scala:866). The typed name's 500 ms debounce fires the lookup during the click, and
  DisableConditionally renders a different tree while loading. Confirm: job log `-> id: search-entry-button]` after
  `subscription payments and then list it *** FAILED ***`. 11187 (parent, fix splice#8418 open), 11300, 11361; 3 of 98
  frontend-wall-clock-time (0) main jobs 2026-11-04 to 11-11.
```
Index table row F: add 11187 to "Parent / main refs" and make State "mixed; 11187 fix splice#8418 open".

## Lesson

Lesson candidate (only once #8418 is merged; it is a resolved-item lesson, not a duplicate one): "Do not hold an
element across a state change you triggered." Typing into a debounced field schedules a re-render; a wait-then-click
on a control that a loading flag wraps differently clicks a node React has already replaced, and a retry of the click
alone can hit the disabled copy. Do: retry find, action and expected outcome together, e.g.
`eventually() { clickOn(find(query)); waitForQuery(resultQuery, Some(5.seconds)) }`, or press Enter in the field.
Not yet in `lessons/2026-W45.md` (checked: no debounce/remount lesson). Backing items: 11187, 11300, 11361.

## Suggested action

Close as duplicate (11187 has an assignee and an open fix PR, so no recurrence comment on the parent; no body file):

```
direnv exec . ci-triage/scripts/close_duplicates.py 11187 11361 --comment 'Same race: search-entry-button went stale between the clickable check and the click in the already taken clue, while the debounced name lookup started.'
```

No Status command (closing moves it to Closed). Assignee: none to add on 11361; the parent 11187 is already assigned
to stephencompall-DA, author of the open fix PR canton-network/splice#8418 (evidence: packet section 6 and 7).
Optional for the user: nudge #8418 (no reviews, blocked on its resource-intensive (0) failure).
