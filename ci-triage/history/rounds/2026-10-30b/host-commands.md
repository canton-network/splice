# Host commands, 2026-10-30b: main compile break (10939 to 11036)

State-guarded: running it twice is harmless. 10939 is closed only once #8150 is merged; re-run later if it is not.

```zsh
R=DACH-NY/cn-test-failures
is_open() { [ "$(gh issue view $1 --repo $R --json state -q .state)" = OPEN ]; }
dup() { is_open $1 && gh issue close $1 --repo $R --reason "not planned" --comment "Duplicate of #10939"; }
is_open 10939 && gh issue view 10939 --repo $R --json title -q .title | grep -q '^GHA Run' && gh issue edit 10939 --repo $R --title "main: Sv1BootstrapProtocolVersion36IntegrationTest does not compile after #7026 (participant admin API auth)"
is_open 10939 && ! gh issue view 10939 --repo $R --comments | grep -q "Semantic merge conflict" && gh issue comment 10939 --repo $R --body "Semantic merge conflict on main: canton-network/splice#7026 (merged 10-30 10:38) changed the participant admin API client config to ClientConfigWithAuth and added a required tokenProvider to ParticipantAdminConnection, while canton-network/splice#8125 added Sv1BootstrapProtocolVersion36IntegrationTest with the old constructor. Every job of the post-merge runs 41042191156, 41044455724 and 41044776115 fails on that test compile (98 issues, 10940 to 11036 closed as duplicates). Fix: canton-network/splice#8150."
dup 10940; dup 10941; dup 10942; dup 10943; dup 10944; dup 10945; dup 10946; dup 10947
dup 10948; dup 10949; dup 10950; dup 10951; dup 10952; dup 10953; dup 10954; dup 10955
dup 10956; dup 10957; dup 10958; dup 10959; dup 10960; dup 10961; dup 10962; dup 10963
dup 10964; dup 10965; dup 10966; dup 10967; dup 10968; dup 10969; dup 10970; dup 10971
dup 10972; dup 10973; dup 10974; dup 10975; dup 10976; dup 10977; dup 10978; dup 10979
dup 10980; dup 10981; dup 10982; dup 10983; dup 10984; dup 10985; dup 10986; dup 10987
dup 10988; dup 10989; dup 10990; dup 10991; dup 10992; dup 10993; dup 10994; dup 10995
dup 10996; dup 10997; dup 10998; dup 10999; dup 11000; dup 11001; dup 11002; dup 11003
dup 11004; dup 11005; dup 11006; dup 11007; dup 11008; dup 11009; dup 11010; dup 11011
dup 11012; dup 11013; dup 11014; dup 11015; dup 11016; dup 11017; dup 11018; dup 11019
dup 11020; dup 11021; dup 11022; dup 11023; dup 11024; dup 11025; dup 11026; dup 11027
dup 11028; dup 11029; dup 11030; dup 11031; dup 11032; dup 11033; dup 11034; dup 11035
dup 11036
[ "$(gh pr view 8150 --repo canton-network/splice --json state -q .state)" = MERGED ] && is_open 10939 && gh issue close 10939 --repo $R --reason completed --comment "Fixed by canton-network/splice#8150."
```
