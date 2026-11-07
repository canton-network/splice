# Context: splice#8361 (simtime 33% slower under DA BFT), comment 2026-11-07 15:31 UTC

Source: https://github.com/canton-network/splice/issues/8361#issuecomment-6709042021 (rautenrieth-da). Summary:

- Under DA BFT each Daml transaction takes about twice as long (+1.2 s): ordering hop p50 0.56-0.64 s vs 0.13-0.23 s;
  confirmation round p50 0.52-0.72 s, p99 15 s vs 4.6-5.8 s. Topology transactions slower by a similar factor.
- The ~15 s tail is one burst (about 85 transactions after advanceTime(PT1H40M) in
  TrafficBasedRewardsSvAppTimeBasedIntegrationTest): ordering stays 1-1.5 s, mediators receive events 15-19 s after
  the sequencers signed them; no view changes, no blacklisting, no GC signs; likely CPU saturation (unconfirmed).
- advanceTime wait: 1.0-1.1 s per call under BFT, under a minute per shard.
- Blacklisting and view changes: once per Canton instance, while sv2-sv4 sequencers are onboarded by the first
  multi-SV test (~12 view changes, then 10-33 s of blacklisted rejections); clients resend; nothing afterwards.
- splice#8356 disables blacklisting in tests (the branch rautenrieth-da/disable-bft-blacklisting-in-tests).
