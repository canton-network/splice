# Host commands, round 3b (10305 10332 10493 10678 10692 10693)

Run on the host (zsh). Nothing here assigns 10332 or comments on 10693; add those by hand once you know the owner
and have the log.

```zsh
gh issue close 10305 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "One-off runner fault (bash could not be exec'd), no recurrence since 10-08; closing as no-repro."
gh issue close 10493 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Coursier cache lock failed with 'Stale file handle' (network FS handle went stale during sbt update). Infra one-off, not #10494; will reopen as a family if it recurs."
gh issue close 10678 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10643"
gh issue close 10692 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10643"
gh issue comment 10693 --repo DACH-NY/cn-test-failures --body "This deploy ran cni 47728087a0 with splice 74cdc0dc8, which already contains the #7178 revert (canton-network/splice#8015), so it is not #10683. First suspects are the key export/download changes that landed with it (canton-network/splice#7977, canton-network/splice#7996, cni #7718). Needs the failing step output of CircleCI job 525503."
```
