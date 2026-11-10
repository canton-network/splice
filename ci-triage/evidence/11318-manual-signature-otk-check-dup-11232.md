# 11318 - ManualSignatureIntegrationTest: validator OTK check gives up while the rotation is in flight (run 42687934161)

DUPLICATE of 11232 (second hit, same test, same mechanism). main 885cd84668 ("Upgrade Canton to
3.7.3-snapshot.20261109.20894.0.ve6e6c252 (#8381)"), job 129400208052 `ci / scala_test_signatures / signatures (0)`,
attempt 1, the only failed job of the run. Tests: succeeded 1, failed 1 (the SV case passed). The test starts alice's
validator with `start()` (not `startSync()`) and polls the OTK for 40 s; the validator rotates its keys only after the
Vet packages init step, which took 33.8 s here (37.2 s in 11232 on Canton 3.7.2). This time the rotation started 1.2 s
before the deadline, and the final OTK (serial 3, without the old keys) reached alice's synchronizer store 104 ms after
the last check. Not a regression from the Canton bump: 11232 is the same failure on Canton 3.7.2, with a longer Vet
packages step, and the shard passed on PR canton-network/splice#8381's own CI.

- Run: https://github.com/canton-network/splice/actions/runs/42687934161, main 885cd84668, job 129400208052 `signatures (0)`.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (3.7.2 before this commit).
- Component: test (timing); the repeated DAR uploads of family AM lengthen the Vet packages step.

Commands run from the worktree root; artifacts in `log/11318/` (gitignored).

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11318/job.log | grep -a -A14 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | cut -c1-400 | head -14
```
```
[info] - should rotate validators' OTK mapping keys that are not signed. *** FAILED ***
[info]   List(1220bea2bad8..., 1220b768965f...) was not empty keys without signatures (ManualSignatureIntegrationTest.scala:47)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
[info]   at org.scalatest.matchers.should.Matchers$AnyShouldWrapper.shouldBe(Matchers.scala:7770)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ManualSignatureIntegrationTest.$anonfun$new$7(ManualSignatureIntegrationTest.scala:47)
[info]   at org.scalatest.AppendedClues$Clueful.withClue(AppendedClues.scala:151)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ManualSignatureIntegrationTest.checkLatestOTKKeysAreSigned$1(ManualSignatureIntegrationTest.scala:47)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ManualSignatureIntegrationTest.$anonfun$new$20(ManualSignatureIntegrationTest.scala:100)
[info]   at scala.runtime.java8.JFunction0$mcV$sp.apply(JFunction0$mcV$sp.scala:18)
[info]   at scala.util.Try$.apply(Try.scala:217)
[info]   at com.digitalasset.canton.BaseTest.clue(BaseTest.scala:404)
[info]   at com.digitalasset.canton.BaseTest.clue$(BaseTest.scala:402)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ManualSignatureIntegrationTest.clue(ManualSignatureIntegrationTest.scala:15)
```
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11318/job.log | grep -a -E 'Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //'
```
```
[info] Run completed in 3 minutes, 15 seconds.
[info] Tests: succeeded 1, failed 1, canceled 0, ignored 0, pending 0
```

## 2. The test (885cd84668)

```
git show 885cd84668:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ManualSignatureIntegrationTest.scala | cat -n | sed -n '30,48p;81,102p'
```
```
    30	    def checkLatestOTKKeysAreSigned(
    31	        otks: Seq[ListOwnerToKeyMappingResult],
    32	        namespace: Namespace,
    33	    ): Unit = {
    34	      val otksForNs = otks
    35	        .filter(_.item.member.namespace == namespace)
    36	      val latestKeys = otksForNs
    37	        .maxBy(_.context.serial)
    38	        .item
    39	        .keys
    40	        .filter {
    41	          case _: SigningPublicKey => true
    42	          case _ => false
    43	        }
    44	        .map(_.id)
    45	        .distinct
    46	      val signatures = otksForNs.flatMap(_.context.signedBy).distinct
    47	      latestKeys.diff(signatures) shouldBe empty withClue "keys without signatures"
    48	    }
    81	    "rotate validators' OTK mapping keys that are not signed." in { implicit env =>
    82	      clue("Initialize the DSO") {
    83	        initDsoWithSv1Only()
    84	      }
    85	      clue("Alice validator starts.") {
    86	        aliceValidatorBackend.start()
    87	      }
    88	
    89	      eventuallySucceeds(40.seconds) {
    90	        val synchronizerId =
    91	          aliceValidatorBackend.participantClientWithAdminToken.synchronizers.id_of(
    92	            aliceValidatorBackend.config.domains.global.alias
    93	          )
    94	        val store = TopologyStoreId.Synchronizer(synchronizerId)
    95	        val otks =
    96	          aliceValidatorBackend.participantClientWithAdminToken.topology.owner_to_key_mappings
    97	            .list(store = Some(store), timeQuery = TimeQuery.Range(None, None))
    98	
    99	        clue("keys are rotated for alice validator's participant.") {
   100	          checkLatestOTKKeysAreSigned(otks, aliceValidatorBackend.participantClient.namespace)
   101	        }
   102	      }
```
The SV case (line 52) uses `sv1Backend.startSync()`; the validator case uses `start()`, so the 40 s budget includes the
whole validator init. The shard runs Canton with `-w -B "scripts/bootstrap/bootstrap-canton-with-unsigned-keys.sc"`
(`.github/workflows/build.yml` line 193), whose `dropSignatures` strips all OTK signatures other than the namespace key
from the genesis state, so every node starts with unsigned OTK keys.

Where the validator rotates (after Vet packages):
```
git show 885cd84668:apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/ValidatorApp.scala | grep -n -E 'appInitStep\("|ensureInitializedWithRotatedOTK' | sed -n 1,40p
```
```
182:            scanConnection <- appInitStep("Getting BFT scan connection") {
195:            _ <- appInitStep("Resolving domain migration id") {
217:                appInitStep("Ensuring decentralized synchronizer already registered") {
221:                appInitStep("Ensuring decentralized synchronizer registered") {
225:            _ <- appInitStep("Ensuring extra domains registered") {
230:            _ <- appInitStep("Vet packages") {
287:                appInitStep("Migrating party data") {
316:                appInitStep("Ensuring user primary party is allocated") {
606:      participantId <- appInitStep("Get participant id") {
621:      scanConnection <- appInitStep("Get scan connection") {
635:      domainMigrationId <- appInitStep("Resolving domain migration id") {
647:      dsoParty <- appInitStep("Get DSO party id") {
687:      _ <- ParticipantInitializer.ensureInitializedWithRotatedOTK(
```
The rotation (`NodeInitializer.performKeyRotation`, NodeInitializer.scala:432-483 at 885cd84668) generates new keys for
the unsigned ones and calls `ensureOwnerToKeyMapping`, which submits two OTKs: one adding the new keys, then one with
exactly the new keys (section 4).

## 3. Timeline: alice's validator reaches the rotation 38.8 s after start, the check stops at 40 s

```
T=log/11318/logs-signatures-0/canton_network_test.clog.gz
zcat $T | grep -a -E 'Running clue: keys are rotated for alice|Running command ListOwnerToKeyMapping.*aliceValidator' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{40}).*/\1 \2/'
```
```
2026-11-09T20:13:47.743Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:13:48.076Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:13:48.629Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:13:49.699Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:13:51.853Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:13:51.895Z Running clue: keys are rotated for alice
2026-11-09T20:13:56.061Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:13:56.085Z Running clue: keys are rotated for alice
2026-11-09T20:14:01.092Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:14:01.120Z Running clue: keys are rotated for alice
2026-11-09T20:14:06.127Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:14:06.155Z Running clue: keys are rotated for alice
2026-11-09T20:14:11.161Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:14:11.177Z Running clue: keys are rotated for alice
2026-11-09T20:14:16.184Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:14:16.206Z Running clue: keys are rotated for alice
2026-11-09T20:14:21.213Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:14:21.237Z Running clue: keys are rotated for alice
2026-11-09T20:14:26.242Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:14:26.268Z Running clue: keys are rotated for alice
2026-11-09T20:14:27.278Z Running command ListOwnerToKeyMapping(Ba
2026-11-09T20:14:27.301Z Running clue: keys are rotated for alice
```
```
zcat $T | grep -a -E 'Finished clue: Alice validator starts|aliceValidator app initialization: (Vet packages finished|Starting initialization)|checking if OTK rotation.*aliceValidator|Test failed' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//; s/1220[0-9a-f]{60}/../g' | cut -c1-220
```
```
2026-11-09T20:13:47.265Z aliceValidator app initialization: Starting initialization
2026-11-09T20:13:47.273Z Finished clue: Alice validator starts.
2026-11-09T20:14:24.777Z aliceValidator app initialization: Vet packages finished after PT33.844627978S
2026-11-09T20:14:26.058Z Node is initialized with identity aliceValidator::1220a13c1158..., checking if OTK rotation is needed
2026-11-09T20:14:27.310Z Test failed: 'ManualSignatureIntegrationTest/synchronizer should rotate validators' OTK mapping keys that are not signed.', message: List(1220bea2bad8..., 1220b768965f...) was not empty keys with
```
`eventuallySucceeds(40.seconds)` started at about 20:13:47.27; the last attempt ran at 20:14:27.278-27.301.

Init steps of alice's validator (the Vet packages step dominates):
```
zcat $T | grep -a -E 'validator=aliceValidator' | grep -a -E 'app initialization: .* finished after|need to be rotated|Success: Key mapping' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//; s/1220[0-9a-f]{60}/../g' | cut -c1-200
```
```
2026-11-09T20:13:47.331Z aliceValidator app initialization: create ledger client finished after PT0.001591633S
2026-11-09T20:13:47.368Z aliceValidator app initialization: Wait for user finished after PT0.036936547S
2026-11-09T20:13:47.604Z aliceValidator app initialization: Getting BFT scan connection finished after PT0.186992442S
2026-11-09T20:13:47.606Z aliceValidator app initialization: Resolving domain migration id finished after PT0.001702167S
2026-11-09T20:13:50.931Z aliceValidator app initialization: Ensuring decentralized synchronizer registered finished after PT3.319273S
2026-11-09T20:13:50.932Z aliceValidator app initialization: Ensuring extra domains registered finished after PT0.00045512S
2026-11-09T20:14:24.777Z aliceValidator app initialization: Vet packages finished after PT33.844627978S
2026-11-09T20:14:25.959Z aliceValidator app initialization: Creating user primary party and waiting for it to be allocated finished after PT1.162959689S
2026-11-09T20:14:25.959Z aliceValidator app initialization: Ensuring user primary party is allocated finished after PT1.182426579S
2026-11-09T20:14:25.970Z aliceValidator app initialization: Get primary party finished after PT0.003692682S
2026-11-09T20:14:25.970Z aliceValidator app initialization: Wait for packages to be uploaded finished after PT0.000132121S
2026-11-09T20:14:25.978Z aliceValidator app initialization: Get participant id finished after PT0.005617978S
2026-11-09T20:14:26.033Z aliceValidator app initialization: Get scan connection finished after PT0.052209615S
2026-11-09T20:14:26.036Z aliceValidator app initialization: Resolving domain migration id finished after PT0.003293851S
2026-11-09T20:14:26.048Z aliceValidator app initialization: Get DSO party id finished after PT0.011276277S
2026-11-09T20:14:26.066Z The following keys (likely created on a version before 0.3.1) need to be rotated because of missing signatures: List(1220bea2bad8..., 1220b768965f...)
2026-11-09T20:14:26.123Z Success: Key mapping for PAR::aliceValidator::1220a13c1158... exists and contains the keys Seq(1220e6d7390f..., 1220bb6138f8..., 1220fbc70a8a...), result is ()
2026-11-09T20:14:26.163Z Success: Key mapping for PAR::aliceValidator::1220a13c1158... exists and contains exactly the keys Seq(1220e6d7390f..., 1220bb6138f8..., 1220fbc70a8a...), result is ()
2026-11-09T20:14:30.361Z aliceValidator app initialization: Onboard validator wallet users finished after PT4.176486554S
```
The keys the validator rotates (`1220bea2bad8..., 1220b768965f...`) are exactly the keys the test reported.

## 4. The rotation was in flight at the last check: serial 3 reached alice's synchronizer store 104 ms later

```
C=log/11318/logs-signatures-0/canton-missing-signatures.clog.gz
zcat $C | grep -a -E '"@timestamp":"2026-11-09T20:14:2[67]' | grep -a 'Persisted topology transactions' | grep -a 'OwnerToKeyMapping(member = PAR::aliceValidator' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Persisted topology transactions \(SequencedTime\(([^)]+)\), EffectiveTime\(([^)]+)\)\).*serial = ([0-9]+).*signatures = ([^"]{0,200}).*"logger_name":"([^"]*)".*/\1 seq=\2 eff=\3 serial=\4 [\6] sigs=\5/; s/1220[0-9a-f]{60}/../g' | cut -c1-330
```
```
2026-11-09T20:14:26.111Z seq=2026-11-09T20:14:26.102413Z eff=2026-11-09T20:14:26.102413Z serial=2 [c.d.c.t.p.TopologyStateProcessorImpl:participant=aliceParticipant/store=Authorized] sigs=Seq(1220a13c1158..., 1220bb6138f8..., 1220e6d7390f...)\n  )\n)
2026-11-09T20:14:26.151Z seq=2026-11-09T20:14:26.143621Z eff=2026-11-09T20:14:26.143621Z serial=3 [c.d.c.t.p.TopologyStateProcessorImpl:participant=aliceParticipant/store=Authorized] sigs=1220a13c1158...\n  )\n)
2026-11-09T20:14:26.485Z seq=2026-11-09T20:14:26.380167Z eff=2026-11-09T20:14:26.630167Z serial=2 [c.d.c.t.p.TopologyStateProcessorImpl:sequencer=globalSequencerSv1/psid=global-domain::1220315fbb3b::36-0] sigs=Seq(1220a13c1158..., 1220bb6138f8..., 1220e6d7390f...)\n  )\n)
2026-11-09T20:14:26.488Z seq=2026-11-09T20:14:26.380167Z eff=2026-11-09T20:14:26.630167Z serial=2 [c.d.c.t.p.TopologyStateProcessorImpl:mediator=globalMediatorSv1/psid=global-domain::1220315fbb3b::36-0] sigs=Seq(1220a13c1158..., 1220bb6138f8..., 1220e6d7390f...)\n  )\n)
2026-11-09T20:14:26.489Z seq=2026-11-09T20:14:26.380167Z eff=2026-11-09T20:14:26.630167Z serial=2 [c.d.c.t.p.TopologyStateProcessorImpl:participant=sv1Participant/psid=global-domain::1220315fbb3b::36-0] sigs=Seq(1220a13c1158..., 1220bb6138f8..., 1220e6d7390f...)\n  )\n)
2026-11-09T20:14:26.489Z seq=2026-11-09T20:14:26.380167Z eff=2026-11-09T20:14:26.630167Z serial=2 [c.d.c.t.p.TopologyStateProcessorImpl:participant=aliceParticipant/psid=global-domain::1220315fbb3b::36-0] sigs=Seq(1220a13c1158..., 1220bb6138f8..., 1220e6d7390f...)\n  )\n)
2026-11-09T20:14:27.401Z seq=2026-11-09T20:14:27.250671Z eff=2026-11-09T20:14:27.500671Z serial=3 [c.d.c.t.p.TopologyStateProcessorImpl:sequencer=globalSequencerSv1/psid=global-domain::1220315fbb3b::36-0] sigs=1220a13c1158...\n  )\n)
2026-11-09T20:14:27.403Z seq=2026-11-09T20:14:27.250671Z eff=2026-11-09T20:14:27.500671Z serial=3 [c.d.c.t.p.TopologyStateProcessorImpl:mediator=globalMediatorSv1/psid=global-domain::1220315fbb3b::36-0] sigs=1220a13c1158...\n  )\n)
2026-11-09T20:14:27.404Z seq=2026-11-09T20:14:27.250671Z eff=2026-11-09T20:14:27.500671Z serial=3 [c.d.c.t.p.TopologyStateProcessorImpl:participant=sv1Participant/psid=global-domain::1220315fbb3b::36-0] sigs=1220a13c1158...\n  )\n)
2026-11-09T20:14:27.405Z seq=2026-11-09T20:14:27.250671Z eff=2026-11-09T20:14:27.500671Z serial=3 [c.d.c.t.p.TopologyStateProcessorImpl:participant=aliceParticipant/psid=global-domain::1220315fbb3b::36-0] sigs=1220a13c1158...\n  )\n)
```
The keys of both serials, from the same log:
```
zcat $C | grep -a -E '"@timestamp":"2026-11-09T20:14:2[67]' | grep -a -o 'Attempting to build, sign, and Replace OwnerToKeyMapping(member = PAR::aliceValidator[^"]*' | sed -E 's/1220[0-9a-f]{60}/../g'
```
```
Attempting to build, sign, and Replace OwnerToKeyMapping(member = PAR::aliceValidator::1220a13c1158..., signingKeys = Seq(1220b768965f..., 1220bb6138f8..., 1220bea2bad8..., 1220e6d7390f...), encryptionKeys = 1220fbc70a8a...) with serial Some(2) 
Attempting to build, sign, and Replace OwnerToKeyMapping(member = PAR::aliceValidator::1220a13c1158..., signingKeys = Seq(1220bb6138f8..., 1220e6d7390f...), encryptionKeys = 1220fbc70a8a...) with serial Some(3) 
```
Serial 2 carries the old and the new keys and is signed by the namespace key and the two new keys only; serial 3
(new keys only) reached alice's synchronizer store at 20:14:27.405, after the last check at 20:14:27.278-27.301. So the
last check saw serial 2 as the latest mapping, whose old keys `1220bea2bad8...` and `1220b768965f...` have no signature
in any OTK: exactly the reported list. The participant's `Success ... exactly the keys` (20:14:26.163) is the
authorized-store state; the synchronizer store follows about 1.2 s later. Hence the check must stay inside
`eventuallySucceeds` even after a `startSync()`.

## 5. Same failure as 11232, on Canton 3.7.2, with a slower Vet packages step

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "keys without signatures"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"'
```
```
#11232 open/- 2026-11-05 ManualSignatureIntegrationTest: validator OTK check gives up before the validator rotates its keys
```
```
gh api repos/DACH-NY/cn-test-failures/issues/11232 --jq '"#\(.number) [\(.state)] \(.title)\nassignees: \([.assignees[].login]|join(","))"'
```
```
#11232 [open] ManualSignatureIntegrationTest: validator OTK check gives up before the validator rotates its keys
assignees: 
```
```
gh api repos/DACH-NY/cn-test-failures/issues/11232/timeline --jq '.[] | "\(.event) \(.created_at) \(.actor.login // "-") \(.source.issue.number // "") \(.label.name // "")"' | tail -8
```
```
added_to_project_v2 2026-11-05T21:03:20Z canton-network-da  
project_v2_item_status_changed 2026-11-05T21:03:22Z github-project-automation[bot]  
renamed 2026-11-05T22:59:45Z rautenrieth-da  
commented 2026-11-05T22:59:59Z rautenrieth-da  
project_v2_item_status_changed 2026-11-05T23:00:04Z rautenrieth-da  
```
The 11232 comment (2026-11-05) already names the fix: `aliceValidatorBackend.startSync()` before the check.
11232 is run 42106471025 (main 64cbca6d3e). Its artifact, same greps:
```
gh run download 42106471025 --repo canton-network/splice -n logs-signatures-0 -D log/11318/run42106471025
zcat log/11318/run42106471025/canton_network_test.clog.gz | grep -a -E 'aliceValidator app initialization: (Vet packages finished|Starting initialization)|checking if OTK rotation.*aliceValidator|Test failed' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//; s/1220[0-9a-f]{60}/../g' | cut -c1-220
git show 64cbca6d3e:nix/canton-sources.json | grep -m1 version
```
```
2026-11-05T21:01:43.850Z aliceValidator app initialization: Starting initialization
2026-11-05T21:02:23.900Z Test failed: 'ManualSignatureIntegrationTest/synchronizer should rotate validators' OTK mapping keys that are not signed.', message: List(12205abedcdb..., 122004aeaf70...) was not empty keys with
2026-11-05T21:02:23.935Z aliceValidator app initialization: Vet packages finished after PT37.195150177S
2026-11-05T21:02:25.453Z Node is initialized with identity aliceValidator::12200bea6109..., checking if OTK rotation is needed
  "version": "3.7.2",
```
Vet packages: 37.2 s on 3.7.2 (11232) vs 33.8 s on 3.7.3-snapshot (11318); the Canton bump did not slow it. No claim
about Canton source is made, so no jar citation is needed.

Canton pin change in #8381 (its only change):
```
git show --stat --format='%H %ad %an%n%s' 885cd84668 | head -5
git diff 885cd84668~1 885cd84668 -- nix/canton-sources.json | grep version
```
```
885cd84668f00691c7fd0b9b858401a63d8dd785 Mon Nov 9 20:55:07 2026 +0100 moritzkiefer-da
Upgrade Canton to 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (#8381)

 nix/canton-sources.json | 12 ++++++------
 1 file changed, 6 insertions(+), 6 deletions(-)
-  "version": "3.7.2",
+  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

## 6. The shard passed on PR #8381 and on 28 of 30 main runs

PR canton-network/splice#8381 CI (head b4eb31098c, same Canton pin):
```
gh api --paginate "repos/canton-network/splice/actions/runs/42659939801/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name | test("signatures")) or .conclusion == "failure" or .conclusion == "cancelled") | "\(.id) attempt=\(.run_attempt) \(.conclusion) \(.started_at) \(.completed_at) \(.name)"'
gh api repos/canton-network/splice/actions/jobs/129309070920/logs > log/11318/pr8381-signatures.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11318/pr8381-signatures.log | grep -a -E 'Tests: succeeded|Run completed' | cut -c1-200
```
```
129308484042 attempt=1 success 2026-11-09T16:58:06Z 2026-11-09T16:58:44Z ci / scala_test_signatures / Split the tests into parallel runs
129309043242 attempt=1 failure 2026-11-09T16:58:59Z 2026-11-09T17:21:35Z ci / scala_test_sim_time / simtime (0)
129309053699 attempt=1 cancelled 2026-11-09T16:59:05Z 2026-11-09T17:54:05Z ci / scala_test_docker_compose / docker-compose (1)
129309059171 attempt=1 failure 2026-11-09T16:59:03Z 2026-11-09T17:45:48Z ci / scala_test_wall_clock_time / wall-clock-time (5)
129309070920 attempt=1 success 2026-11-09T16:59:01Z 2026-11-09T17:13:19Z ci / scala_test_signatures / signatures (0)
129340546097 attempt=1 failure 2026-11-09T17:54:14Z 2026-11-09T17:54:54Z ci / final_result
129372977334 attempt=2 success 2026-11-09T16:59:01Z 2026-11-09T17:13:19Z ci / scala_test_signatures / signatures (0)
129373033399 attempt=2 success 2026-11-09T16:58:06Z 2026-11-09T16:58:44Z ci / scala_test_signatures / Split the tests into parallel runs
2026-11-09T17:12:51.2391184Z [info] Run completed in 2 minutes, 20 seconds.
2026-11-09T17:12:51.2392680Z [info] Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0
```
(Passing jobs upload no artifact, so the PR's Vet packages duration is not available.)

Main history of the signatures shard (workflow "CI post-merge to main or release-line branches", last 30 runs):
```
WF=163795441; for r in $(gh api "repos/canton-network/splice/actions/workflows/$WF/runs?branch=main&per_page=30" --jq '.workflow_runs[].id'); do j=$(gh api --paginate "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=100" --jq '.jobs[] | select(.name | endswith(" / signatures (0)")) | "\(.id) a=\(.run_attempt) \(.conclusion)"' 2>/dev/null | head -1); id=${j%% *}; rc=""; if [ -n "$id" ]; then [ -s log/11318/hist/$id.log ] || gh api repos/canton-network/splice/actions/jobs/$id/logs > log/11318/hist/$id.log 2>/dev/null; rc=$(sed -E 's/\x1b\[[0-9;]*m//g' log/11318/hist/$id.log | grep -a -E 'Run completed in|Tests: succeeded' | sed -E 's/^[^Z]*Z \[info\] //' | tr '\n' ' '); fi; echo "$r $j | $rc"; done
```
```
42687934161 129400208052 a=1 failure | Run completed in 3 minutes, 15 seconds. Tests: succeeded 1, failed 1, canceled 0, ignored 0, pending 0 
42658360509 129303338579 a=1 success | Run completed in 2 minutes, 35 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42646231899 129260605829 a=1 success | Run completed in 2 minutes, 34 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42639148146 129235728878 a=1 success | Run completed in 2 minutes, 20 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42639073931 129235473353 a=1 success | Run completed in 2 minutes, 51 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42637895044 129231297123 a=1 success | Run completed in 2 minutes, 14 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42634636103 129219892316 a=1 success | Run completed in 2 minutes, 3 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42623223930 129180742902 a=1 success | Run completed in 2 minutes, 18 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42620710625 129172167329 a=1 success | Run completed in 2 minutes, 20 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42618450792 129164508650 a=1 success | Run completed in 2 minutes, 16 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42608940134 129132347430 a=1 success | Run completed in 2 minutes, 3 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42591855865 129075446254 a=1 success | Run completed in 3 minutes, 15 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42288740264 128136688010 a=1 success | Run completed in 2 minutes, 32 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42258277175 128031675058 a=1 success | Run completed in 2 minutes, 8 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42253557708 128015875410 a=1 success | Run completed in 2 minutes, 16 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42253265180 128014067832 a=1 success | Run completed in 2 minutes, 17 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42248944117 127999190072 a=1 success | Run completed in 2 minutes, 13 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42230239729 127934641961 a=1 success | Run completed in 2 minutes, 24 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42226645323 127922375411 a=1 success | Run completed in 2 minutes, 20 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42214429624 127881405684 a=1 success | Run completed in 2 minutes, 26 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42209420702 127864704009 a=1 success | Run completed in 2 minutes, 20 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42205124002 127850216980 a=1 success | Run completed in 2 minutes, 18 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42199308951 127831003505 a=1 success | Run completed in 2 minutes, 23 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42123783876 127583489969 a=1 success | Run completed in 2 minutes, 58 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42109599444 127533996159 a=1 success | Run completed in 2 minutes, 18 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42106471025 127524013040 a=1 failure | Run completed in 4 minutes, 9 seconds. Tests: succeeded 1, failed 1, canceled 0, ignored 0, pending 0 
42083624555 127444391659 a=1 success | Run completed in 2 minutes, 13 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42062875792 127369914440 a=1 success | Run completed in 2 minutes, 29 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42054420521 127340994106 a=1 success | Run completed in 2 minutes, 54 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
42043772809 127301781107 a=1 success | Run completed in 2 minutes, 13 seconds. Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0 
```
The two failures are 11232 (42106471025, Canton 3.7.2) and 11318 (42687934161). Run 42591855865 (Canton 3.7.2) took as
long as this failure and passed.
```
git show 9dafafbece:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.2",
```
885cd84668 is still the newest main run of the workflow (checked 2026-11-09T21:01Z):
```
gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&per_page=5" --jq '.workflow_runs[] | "\(.id) \(.head_sha[0:10]) \(.created_at) \(.status) \(.conclusion)"'
```
```
42687934161 885cd84668 2026-11-09T19:55:11Z completed failure
42658360509 91715b25c0 2026-11-09T16:47:50Z completed failure
42646231899 5dc875a64f 2026-11-09T15:35:40Z completed failure
42639148146 92705421fd 2026-11-09T14:53:49Z completed failure
42639073931 fbc7ad2481 2026-11-09T14:53:21Z completed failure
```

## 7. A quarter of alice's DAR uploads are repeats (family AM mechanism)

```
for T in log/11318/logs-signatures-0/canton_network_test.clog.gz log/11318/run42106471025/canton_network_test.clog.gz; do echo "== $T"; zcat $T | grep -a 'validator=aliceValidator' | grep -a -o 'Sending upload dar for [^"]*' | awk '{n++; c[$5]++} END{d=0; r=0; for(k in c){d++; if(c[k]>1) r+=c[k]-1}; print "uploads="n, "distinct="d, "repeats="r}'; done
```
```
== log/11318/logs-signatures-0/canton_network_test.clog.gz
uploads=79 distinct=58 repeats=21
== log/11318/run42106471025/canton_network_test.clog.gz
uploads=79 distinct=58 repeats=21
```
Both repeats of a DAR are sent as separate `UploadDar` requests (e.g. `splice-api-token-allocation-v1-1.0.0`, tids
c266c487... and 37deca8c..., both `succeeded(OK)` at 20:14:15.57 and 20:14:15.64). The dedup fix proposed for family AM
(11299) would shorten this step, but the test would stay racy without waiting for init.

## 8. Fix state

```
git log --format='%h %ad %an %s' --date=short 64cbca6d3e..885cd84668 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ManualSignatureIntegrationTest.scala; echo "(end of log)"
gh api -X GET search/issues -f q='repo:canton-network/splice is:pr 11232' --jq '.items[] | "#\(.number) \(.state) \(.title[0:100])"'; echo "(end of search)"
git blame -s --line-porcelain -L81,102 885cd84668 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ManualSignatureIntegrationTest.scala | awk '/^author /{a[$0]++} END{for(k in a) print a[k], k}'
```
```
(end of log)
(end of search)
22 author Julien Tinguely
```
No change to the test since 11232 and no PR naming 11232. Lines 81-102 all come from 196fc46c13 ("Rotate OTK keys with
missing signatures (#1912)", Julien Tinguely).

## Verdict

Duplicate of 11232 (open, unassigned; its board Status was changed by rautenrieth-da on 2026-11-05, value not read): flake from test timing, not a Canton regression. Fix
in the test (as 11232 says): `aliceValidatorBackend.startSync()` instead of `start()` at
ManualSignatureIntegrationTest.scala:86, keeping the check in `eventuallySucceeds` (section 4: the synchronizer store
trails the participant's `Success` by about 1.2 s). Optional: the AM dedup of repeated DAR uploads shortens the
Vet packages step for every validator. Frequency: 2 of the last 30 main runs (2026-11-05 and 2026-11-09).

Not verified: the Vet packages duration in passing runs (no artifacts), so the margin in a typical pass is unknown;
PR-run failures of this shard were not searched beyond PR #8381.
