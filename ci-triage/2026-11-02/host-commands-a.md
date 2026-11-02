# Host commands, 2026-11-02 fork a: mainnet-Daml nightlies 41171022068, 41275427340, 41391921766

Run on the host (zsh). State-guarded: running it twice is harmless. Evidence: `nightly-a.md`.
Parents stay open: 10927 (X2, Moritz), 10930 and 10933 (Y, no owner yet). All 25 per-job issues are dups.

```zsh
R=DACH-NY/cn-test-failures
is_open() { [ "$(gh issue view $1 --repo $R --json state -q .state)" = OPEN ]; }
not_commented() { ! gh issue view $1 --repo $R --json comments -q '.comments[] | select(.author.login == "ray-roestenburg-da") | .body' | grep -qF "$2"; }

is_open 10927 && not_commented 10927 "Still failing 10-31, 11-01, 11-02" && gh issue comment 10927 --repo $R --body "Still failing 10-31, 11-01, 11-02 (mainnet-Daml nightly on main 3525c637db, with Canton 3.7.1 #8165): 16 per-job issues, every flagged line is 'synchronizer outbox flusher failed' (OTK MED::svN on ::36-0; on 11-02 roll-forward-lsu NamespaceDelegation + OTK on the LSU successor ::37-2). Dups closed: 11108 11109 11110 11111 11113 11115 11120 11121 11122 11123 11126 11127 11129 11132 11133 11135."
is_open 10930 && not_commented 10930 "Still failing 10-31, 11-01, 11-02" && gh issue comment 10930 --repo $R --body "Still failing 10-31, 11-01, 11-02 (main 3525c637db): line 206 'Vector() was empty', ProcessRewardsTrigger only ran BatchOfMintingAllowances. Dups closed: 11112 11119 11134."
is_open 10933 && not_commented 10933 "Still failing 10-31, 11-01, 11-02" && gh issue comment 10933 --repo $R --body "Still failing 10-31, 11-01, 11-02 (main 3525c637db): UNKNOWN_INFORMEES right after the token-standard transfer to splice_traffic-manager, in TokenStandardTransferIntegrationTest and TokenStandardV2TransferIntegrationTest. Dups closed: 11114 11116 11124 11125 11130 11131."

is_open 11108 && gh issue close 11108 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11109 && gh issue close 11109 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11110 && gh issue close 11110 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11111 && gh issue close 11111 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11113 && gh issue close 11113 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11115 && gh issue close 11115 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11120 && gh issue close 11120 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11121 && gh issue close 11121 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11122 && gh issue close 11122 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11123 && gh issue close 11123 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11126 && gh issue close 11126 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11127 && gh issue close 11127 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11129 && gh issue close 11129 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11132 && gh issue close 11132 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11133 && gh issue close 11133 --repo $R --reason "not planned" --comment "Duplicate of #10927"
is_open 11135 && gh issue close 11135 --repo $R --reason "not planned" --comment "Duplicate of #10927"

is_open 11112 && gh issue close 11112 --repo $R --reason "not planned" --comment "Duplicate of #10930"
is_open 11119 && gh issue close 11119 --repo $R --reason "not planned" --comment "Duplicate of #10930"
is_open 11134 && gh issue close 11134 --repo $R --reason "not planned" --comment "Duplicate of #10930"

is_open 11114 && gh issue close 11114 --repo $R --reason "not planned" --comment "Duplicate of #10933"
is_open 11116 && gh issue close 11116 --repo $R --reason "not planned" --comment "Duplicate of #10933"
is_open 11124 && gh issue close 11124 --repo $R --reason "not planned" --comment "Duplicate of #10933"
is_open 11125 && gh issue close 11125 --repo $R --reason "not planned" --comment "Duplicate of #10933"
is_open 11130 && gh issue close 11130 --repo $R --reason "not planned" --comment "Duplicate of #10933"
is_open 11131 && gh issue close 11131 --repo $R --reason "not planned" --comment "Duplicate of #10933"
```
