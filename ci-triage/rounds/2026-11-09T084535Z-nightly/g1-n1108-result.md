# g1-n1108 (run 42449239264, mainnet nightly 2026-11-08, main 0b2ccec235)

Packet: evidence/11277-11278-11279-mainnet-nightly-1108.md

- 11277 simtime (5): dup 11274 (TrafficBasedRewardsDryRun..., extraFeaturedAssetActivityWeight downgrade, sha unchanged, #8362 open)
- 11278 simtime (3): dup 11274 (concrete TrafficBasedRewardsTimeBasedIntegrationTest, same error)
- 11279 docker-no-canton (0): new, infra one-off, first hit: Testcontainers pull watchdog on adobe/s3mock:4.11.0, retry succeeded, tests passed

Action:
  direnv exec . ci-triage/scripts/close_duplicates.py 11274 11277 11278 --comment 'Same sha and same sv1 bootstrap failure: extraFeaturedAssetActivityWeight may not be dropped during downgrading, in the TrafficBasedRewards tests that still run against mainnet Daml'
  gh issue comment 11279 -R DACH-NY/cn-test-failures --body-file ci-triage/rounds/2026-11-09T084535Z-nightly/comments/11279.md
  gh issue edit 11279 -R DACH-NY/cn-test-failures --title 'docker-no-canton: Testcontainers pull watchdog ERROR on a stalled adobe/s3mock pull (retry succeeded)'
  direnv exec . ci-triage/scripts/set_status.py Inbox Triaged 11279
Lesson: none
