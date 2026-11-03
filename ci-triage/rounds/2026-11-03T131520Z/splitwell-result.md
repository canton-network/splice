# Group splitwell (round 2026-11-03T131520Z)

Ref 11161: run 41633306529, job 125908678411 `frontend-wall-clock-time (1)`, main be675a11dd (#8040 react-router 8).
Packet: `evidence/11161-splitwell-wallet-login-already-logged-in-race.md`.

Verdict: new family AE (provisional letter). This is a test-side race in `FrontendLoginUtil.loginOnceConfirmedToBeAtUrl`.
Bob was still logged in to the wallet from test case 0, and the confirm-payment page loaded in 3.8 s. The one-shot
`#logout-button` check ran before React rendered, so the helper waited for a login form that never comes, and the 5 s
budget expired at 12:26:50. Not caused by #8040: shard (1) passed on the next two main runs (6a32b00e92, 5c53b74294).
Not a duplicate of 10156 / 9704 (F, blocked vite modules; no MIME errors here) or of 10893.

Fix proposed, not written: in `apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/FrontendLoginUtil.scala`,
`loginOnceConfirmedToBeAtUrl` (lines 39-63 at be675a11dd), before the `eventually(timeUntilSuccess = 5.seconds)`
block add `eventually() { (find(id("logout-button")).isDefined || find(id("user-id-field")).isDefined) shouldBe true }`.

## INDEX.tsv row

```
11161	2026-11-03	41633306529	main be675a11dd	125908678411 frontend-wall-clock-time (1)	3.7.1	SplitwellFrontendIntegrationTest settle debts with a single party: bob already logged in to wallet; confirm-payment page DOMContentLoaded 12:26:47.022 (3.8 s after click), one-shot logout-button checks at 12:26:43.883 and 12:26:47.030 miss it, user-id-field never shown, 5 s login budget expires 12:26:50.118	new (AE)	test flake	open; test fix proposed in FrontendLoginUtil.loginOnceConfirmedToBeAtUrl, no branch	evidence/11161-splitwell-wallet-login-already-logged-in-race.md
```

## known-families.md

Index row:
```
| AE | frontend login helper misses already-logged-in state on a slow page load | 11161 | open, test fix proposed |
```
Section:
```
## AE. Frontend login helper misses the already-logged-in state on a slow page load
- Signature: `Could not find IdQuery(user-id-field)` from `FrontendLoginUtil.loginOnceConfirmedToBeAtUrl`, on a page
  where the user was already logged in (failure screenshot shows `Logout`), no `disallowed MIME type` in the browser log.
- Confirm: in the browser log, the `#logout-button` FindElement of each attempt returns `no such element` before or
  right after `browsingContext.domContentLoaded` of the target page (11161: 12:26:43.883 and 12:26:47.030, DCL
  12:26:47.022); in canton_network_test.clog two `Failed clue: Waiting for login page to be ready` within 5 s.
- Mechanism: the helper checks for the logout button once per attempt, then waits 3 s for the login form; the 5 s
  budget allows two attempts, so a page that renders more than about 2 s after the helper starts is never logged out.
  Seen on wallet `/confirm-payment/<cid>` after the splitwell redirect (Vite dev server, 3.8 s load). Precedent for the
  same helper: #7810 (SvFrontendIntegrationTest auto-login race). Different from F (blocked vite modules).
- Parent 11161 (SplitwellFrontendIntegrationTest settle debts with a single party, main be675a11dd).
- Fix: wait for either `#logout-button` or `#user-id-field` before the retry loop (packet 11161). Not written.
```

## Issue commands

```
gh issue comment 11161 -R DACH-NY/cn-test-failures --body 'SplitwellFrontendIntegrationTest settle debts with a single party failed in the wallet login helper, not in splitwell. Bob was still logged in to the wallet (localhost:3001) from test case 0. After the splitwell transfer redirected to /confirm-payment, the wallet page reached DOMContentLoaded only at 12:26:47.022, 3.8 s after the click. FrontendLoginUtil.loginOnceConfirmedToBeAtUrl checks for logout-button once per attempt (12:26:43.883 and 12:26:47.030, both before React rendered), so it never logged out and waited for user-id-field, which a logged-in wallet never shows; the 5 s budget ended at 12:26:50 with the failure screenshot showing the logged-in wallet. Not caused by #8040 (react-router 8): the same shard passed on the next two main runs. Test fix: before the 5 s retry loop, wait until either logout-button or user-id-field is present, then decide. Packet: ci-triage/evidence/11161-splitwell-wallet-login-already-logged-in-race.md on the triage branch.'
gh issue edit 11161 -R DACH-NY/cn-test-failures --title 'SplitwellFrontendIntegrationTest: wallet login helper misses the already-logged-in state when the confirm-payment page loads slowly'
direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11161
```
