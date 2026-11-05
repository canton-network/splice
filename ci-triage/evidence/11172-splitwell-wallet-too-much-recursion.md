# 11172 - SplitwellFrontendIntegrationTest settle debts multiple parties: bob's wallet page crashed with "too much recursion" right after login (run 41674340437)

new, rare. main 8dacec5c10, job 126053217451 `frontend-wall-clock-time (1)`. Not family AE: AE fails with `Could not find
IdQuery(user-id-field)`; here the login form was found and submitted, and the page then crashed in React render with a
Firefox stack overflow, so neither `logout-button` nor `loginFailed` ever appeared. The run does not contain PR 8227
(AE fix), but 8227 only lengthens the first login phase and would not have helped.

- Run: https://github.com/canton-network/splice/actions/runs/41674340437, main 8dacec5c10 ("Monitor event count and event rate (#8207)"), job 126053217451 `frontend-wall-clock-time (1)`, attempt 1.
- Component: frontend (wallet, dev build via vite).

## 1. Failing assertion

```
grep -a -A2 'settle debts with multiple parties \*\*\* FAILED' log/11172/job.txt | head -3
```
```
[info] - should settle debts with multiple parties *** FAILED ***
[info]   [frontend=bobSplitwell step=settle-debts-and-verify] false was not equal to true (FrontendLoginUtil.scala:61)
```
Line 61 (at 8dacec5c10) is `(find(id("logout-button")).isDefined || find(id("loginFailed")).isDefined) shouldBe true`
inside `eventually()` after the login click.

## 2. Login timeline (canton_network_test.clog)

```
zcat log/11172/art/canton_network_test.clog.gz | grep -a '"logger_name":"o.l.s.i.t.SplitwellFrontendIntegrationTest:SplitwellFrontendIntegrationTest' | grep -aE '16:46:(1[01]|3[01])' | grep -a clue
```
```
16:46:11.524 Failed clue: Waiting for login page to be ready ... Could not find IdQuery(user-id-field)
16:46:11.654 Finished clue: Waiting for login page to be ready
16:46:11.844 Finished clue: Waiting for IdQuery(login-button) to be clickable
16:46:11.924 Running clue: Waiting for UI elements to show up that indicate login success or failure
16:46:31.931 Failed clue: Waiting for UI elements to show up that indicate login success or failure
```
(The first attempt ran 3 ms after `domContentLoaded` of `http://localhost:3001/confirm-payment/...` at 16:46:11.521; the
retry found the form.)

## 3. The crash (browser log of bobSplitwell, epoch ms)

```
grep -a 'log.entryAdded' log/11172/bob-window.txt | grep -a 'recursion' | grep -aoE '^[0-9]+|"level":"[a-z]+"|"text":"[^"]{0,120}' | paste - - -
```
```
1793724371906	"level":"error"	"text":"InternalError: too much recursion \n\nThe above error occurred in the <Insertion> component.\n\nReact will try to recreate
1793724371907	"level":"error"	"text":"React Router caught the following error during render InternalError: too much recursion
```
1793724371906 = 16:46:11.906, 62 ms after the login click. Page source saved at the failure:
```
zcat log/11172/art/webpage-26-11-03-16.46.32.7.html.gz | sed 's/<[^>]*>/ /g' | tr -s ' \n' ' '
```
```
 You need to enable JavaScript to run this app. Something went wrong! An unexpected error has occurred. too much recursion Return to Home
```

## 4. History

`too much recursion` before: 3266 (2025-03), 7039 (2026-01, react-query-devtools / solid.js), 8733 and 9312 (2026-06/07,
devtools), fixed by splice#6577 (2026-09-15, devtools gated in integration tests). This one is in Emotion's
`<Insertion>` (MUI styling), not devtools; first hit since 09-15.

## Verdict
One-off frontend crash (Firefox stack overflow while rendering the wallet after login, dev build). Rare. Not AE, not the
devtools issue. Not verified: what recursed (the error has no component stack beyond `<Insertion>`).
