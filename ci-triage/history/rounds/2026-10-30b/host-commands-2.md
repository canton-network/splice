# Host commands, 2026-10-30b part 2: late arrivals of the main compile break (11037 to 11070)

11037: last job of run 41044776115. 11038 to 11070: post-merge run 41047059702 on 5adf8c654f (#8096), merged just
before the fix #8150. All 34 first errors are the Sv1BootstrapProtocolVersion36IntegrationTest compile error.
State-guarded: running it twice is harmless.

```zsh
R=DACH-NY/cn-test-failures
is_open() { [ "$(gh issue view $1 --repo $R --json state -q .state)" = OPEN ]; }
dup() { is_open $1 && gh issue close $1 --repo $R --reason "not planned" --comment "Duplicate of #10939 (fixed by canton-network/splice#8150)"; }
dup 11037; dup 11038; dup 11039; dup 11040; dup 11041; dup 11042; dup 11043; dup 11044
dup 11045; dup 11046; dup 11047; dup 11048; dup 11049; dup 11050; dup 11051; dup 11052
dup 11053; dup 11054; dup 11055; dup 11056; dup 11057; dup 11058; dup 11059; dup 11060
dup 11061; dup 11062; dup 11063; dup 11064; dup 11065; dup 11066; dup 11067; dup 11068
dup 11069; dup 11070
```
