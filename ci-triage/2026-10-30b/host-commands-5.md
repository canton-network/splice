# Host commands, 2026-10-30b part 5: re-run of the broken post-merge run (11071 to 11103)

Run 41047059702 on 5adf8c654f (#8096) was re-run at 12:49 by jagathweerasinghe-da (attempt 2). That commit predates
the fix #8150, so all 33 failed jobs fail again on the Sv1BootstrapProtocolVersion36IntegrationTest compile error
(checked every job's first error). State-guarded.

```zsh
R=DACH-NY/cn-test-failures
is_open() { [ "$(gh issue view $1 --repo $R --json state -q .state)" = OPEN ]; }
dup() { is_open $1 && gh issue close $1 --repo $R --reason "not planned" --comment "Duplicate of #10939: re-run of run 41047059702 on 5adf8c654f, which predates the fix canton-network/splice#8150"; }
dup 11071; dup 11072; dup 11073; dup 11074; dup 11075; dup 11076; dup 11077; dup 11078
dup 11079; dup 11080; dup 11081; dup 11082; dup 11083; dup 11084; dup 11085; dup 11086
dup 11087; dup 11088; dup 11089; dup 11090; dup 11091; dup 11092; dup 11093; dup 11094
dup 11095; dup 11096; dup 11097; dup 11098; dup 11099; dup 11100; dup 11101; dup 11102
dup 11103
```
