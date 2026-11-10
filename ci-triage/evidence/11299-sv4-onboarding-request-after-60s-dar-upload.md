# 11299 - SvTimeBasedOnboardingIntegrationTest: sv4 requests onboarding 90 s after start, test waits 60 s (run 42634636103)

new. Post-merge main dbcb0c14ca (#8349), job `simtime (5)`, Canton 3.7.2. 4 tests succeeded, 1 failed:
SvTimeBasedOnboardingIntegrationTest `expire stale ...` at the clue "An `SvOnboardingRequest` contract is created",
an `eventually(timeUntilSuccess = 60.seconds)` that waits for the `SvOnboardingRequest` sv4 creates during its SvApp
init. sv4 was started at 14:58:43.5 and logged `Requesting to be onboarded via the sponsor SV` at 15:00:13.913, 30 s
after the clue failed (14:59:43.521). 81 s of the 90 s went into uploading 87 DARs to sv4Participant (14:58:48.310 to
15:00:09.034), five at a time, about 6 s per batch. The same 87 uploads took 31 s for sv1 earlier in the shard and
67 to 69 s for sv2 and sv3 in this test's setup. Test timeout too tight for the joining SV's init; not the 11255 BFT
topology family (sv4 never got to sequencer onboarding) and not family U (10907, ACS import, later in the same test).

- Run: https://github.com/canton-network/splice/actions/runs/42634636103, main dbcb0c14ca ("WG and Portfolio UI version bump automation (#8349)"), job 129219877052 `ci / scala_test_sim_time / simtime (5)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: test.

## 1. Failing assertion

The clue waits for exactly one `SvOnboardingRequest` in sv1's ACS and finds none.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11299/job.log | grep -a -A8 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | head -5
```
```
[info] - expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts *** FAILED ***
[info]   List() had length 0 instead of expected length 1 (SvTimeBasedOnboardingIntegrationTest.scala:75)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
[info]   at org.scalatest.matchers.should.Matchers$ResultOfHaveWordForExtent.length(Matchers.scala:2780)
```

The test at the run's sha: sv2's and sv3's `SvOnboardingRequestTrigger` are paused, sv4 is started, and the request
sv4 creates during SvApp init must appear within 60 s.

```
git show dbcb0c14ca:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvTimeBasedOnboardingIntegrationTest.scala | sed -n '57,79p'
```
```
        val sv2and3OnboardingRequestTriggers =
          Seq(sv2Backend, sv3Backend).map(_.dsoAutomation.trigger[SvOnboardingRequestTrigger])

        MonadUtil
          .parTraverseWithLimit_(PositiveInt.tryCreate(2))(sv2and3OnboardingRequestTriggers)(
            _.pause()
          )
          .futureValue
        // We now need 2 confirmations to execute an action, but only sv1 will confirm to onboard sv4.
        clue("SV4 starts") {
          sv4ValidatorBackend.start()
          sv4Backend.start()
        }
        clue("An `SvOnboardingRequest` contract is created") {
          // Increased timeout, because SV4 takes a while to start up
          eventually(timeUntilSuccess = 60.seconds)(
            // The onboarding is requested by SV4 during SvApp init.
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(splice.svonboarding.SvOnboardingRequest.COMPANION)(
                dsoParty
              ) should have length 1
          )
        }
```

The 60 s value dates from the initial import:

```
git log --format='%h %ad %s' --date=short -S'timeUntilSuccess = 60.seconds' dbcb0c14ca -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvTimeBasedOnboardingIntegrationTest.scala | tail -1
```
```
3052b21184 2024-10-18 Update Splice from CCI (#70)
```

## 2. Test timeline

```
zcat log/11299/logs-simtime-5/canton_network_test.clog.gz | grep -a 'SvTimeBasedOnboardingIntegrationTest' | grep -a -E 'Running clue|Finished clue|Failed clue|Test failed' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-110
```
```
2026-11-09T14:56:18.991Z Running clue: Initialize DSO with 3 SVs",[o.l.s.i.t.SvTimeBasedOnboardingIntegrationT
2026-11-09T14:58:42.025Z Finished clue: Initialize DSO with 3 SVs",[o.l.s.i.t.SvTimeBasedOnboardingIntegration
2026-11-09T14:58:42.026Z Running clue: expire stale `SvOnboardingRequest` contracts.",[o.l.s.i.t.SvTimeBasedOn
2026-11-09T14:58:42.029Z Running clue: SV4 starts",[o.l.s.i.t.SvTimeBasedOnboardingIntegrationTest:SvTimeBased
2026-11-09T14:58:43.499Z Finished clue: SV4 starts",[o.l.s.i.t.SvTimeBasedOnboardingIntegrationTest:SvTimeBase
2026-11-09T14:58:43.500Z Running clue: An `SvOnboardingRequest` contract is created",[o.l.s.i.t.SvTimeBasedOnb
2026-11-09T14:59:43.521Z Failed clue: An `SvOnboardingRequest` contract is created",[o.l.s.i.t.SvTimeBasedOnbo
2026-11-09T14:59:43.521Z Failed clue: expire stale `SvOnboardingRequest` contracts.",[o.l.s.i.t.SvTimeBasedOnb
2026-11-09T15:00:18.458Z Test failed: 'SvTimeBasedOnboardingIntegrationTest/expire stale `SvOnboardingRequest`
```

## 3. sv4 SvApp init: where the 90 s went

sv4's init steps, the DAR upload span, and the onboarding request (JoiningNodeInitializer), for every node that ran
the joining-node DAR upload in this shard. Columns: suite, node, step, count, first, last.

```
zcat log/11299/logs-simtime-5/canton_network_test.clog.gz | grep -a -E 'Sending upload dar|Vetting packages based on state from sponsor|Packages vetting completed|Requesting to be onboarded via the sponsor SV' | grep -a -o -E '"@timestamp":"[^"]+","message":"(Sending upload dar|Vetting packages|Packages vetting completed|Requesting to be onboarded)[^"]*","logger_name":"[^"]*"' | sed -E 's/"@timestamp":"([^"]+)","message":"([A-Za-z]+ [a-z]+)[^"]*","logger_name":"[^:]*:([^/"]*)\/[^"]*(SV|validator)=([^/"]*).*/\3 \5 \2 \1/' | awk '{k=$1" "$2" "$3" "$4; if(!(k in first)) {first[k]=$5; order[++n]=k} last[k]=$5; cnt[k]++} END{for(i=1;i<=n;i++){k=order[i]; print k, cnt[k], first[k], last[k]}}' | grep -a -E '^(TrafficBasedRewardsDryRun|SvTimeBasedOnboarding)[A-Za-z]* sv'
```
```
TrafficBasedRewardsDryRunTimeBasedIntegrationTest sv1 Sending upload 87 2026-11-09T14:43:14.136Z 2026-11-09T14:43:45.247Z
SvTimeBasedOnboardingIntegrationTest sv2 Vetting packages 1 2026-11-09T14:56:27.384Z 2026-11-09T14:56:27.384Z
SvTimeBasedOnboardingIntegrationTest sv2 Sending upload 87 2026-11-09T14:56:27.412Z 2026-11-09T14:57:34.279Z
SvTimeBasedOnboardingIntegrationTest sv3 Vetting packages 1 2026-11-09T14:56:27.503Z 2026-11-09T14:56:27.503Z
SvTimeBasedOnboardingIntegrationTest sv3 Sending upload 87 2026-11-09T14:56:27.536Z 2026-11-09T14:57:36.250Z
SvTimeBasedOnboardingIntegrationTest sv2 Packages vetting 1 2026-11-09T14:57:36.790Z 2026-11-09T14:57:36.790Z
SvTimeBasedOnboardingIntegrationTest sv2 Requesting to 1 2026-11-09T14:57:37.041Z 2026-11-09T14:57:37.041Z
SvTimeBasedOnboardingIntegrationTest sv3 Packages vetting 1 2026-11-09T14:57:38.428Z 2026-11-09T14:57:38.428Z
SvTimeBasedOnboardingIntegrationTest sv3 Requesting to 1 2026-11-09T14:57:38.525Z 2026-11-09T14:57:38.525Z
SvTimeBasedOnboardingIntegrationTest sv4 Vetting packages 1 2026-11-09T14:58:48.277Z 2026-11-09T14:58:48.277Z
SvTimeBasedOnboardingIntegrationTest sv4 Sending upload 87 2026-11-09T14:58:48.310Z 2026-11-09T15:00:09.034Z
SvTimeBasedOnboardingIntegrationTest sv4 Packages vetting 1 2026-11-09T15:00:13.577Z 2026-11-09T15:00:13.577Z
SvTimeBasedOnboardingIntegrationTest sv4 Requesting to 1 2026-11-09T15:00:13.913Z 2026-11-09T15:00:13.913Z
```

So for sv4: start 14:58:43.5, DAR upload 14:58:48.3 to 15:00:09.0 (81 s), vetting done 15:00:13.6, onboarding request
15:00:13.9, i.e. 90 s after start and 30 s after the clue gave up. sv2 and sv3 (setup, waited for by
`startAllSync` with its 5 min `waitForInitialization`) took 67 to 69 s for the same 87 uploads, sv1 31 s at 14:43.

sv4's init before the upload was quick (participant, user, synchronizer registration, DSO party check within 5 s):

```
zcat log/11299/logs-simtime-5/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-09T14:58:4[3-8]' | grep -a -E '/SV=sv4"' | grep -a -E 'app initialization: Starting|Wait for user finished|Success: participant registered|Participant hosts dsoParty|Success: User sv4-eed41b51 has primary party|Vetting packages based on state' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/\2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-150
```
```
2026-11-09T14:58:43.492Z sv4 app initialization: Starting initialization",INFO
2026-11-09T14:58:43.582Z sv4 app initialization: Wait for user finished after PT0.032857593S",INFO
2026-11-09T14:58:43.718Z Success: participant registered Synchronizer 'global', result is RegisteredSynchronizer(\n  SynchronizerConnectionConfig(\n  
2026-11-09T14:58:47.200Z Participant hosts dsoParty: false and has proposals to host dsoParty false",INFO
2026-11-09T14:58:48.277Z Vetting packages based on state from sponsor",INFO
```

## 4. The uploads themselves

Each `UploadDar` to sv4Participant took 3.5 to 7 s; five run in parallel, the next five start when the batch is done
(first four batches shown).

```
zcat log/11299/logs-simtime-5/canton_network_test.clog.gz | grep -a -E '/SV=sv4' | grep -a -E 'PackageService/UploadDar' | grep -a -E 'sending request|succeeded' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Request \(tid:([0-9a-f]{8})[0-9a-f]*\) ([^ ]+) to [^:]+:[0-9]+: ([a-z ]+).*/\1 \2 \4/' | head -20
```
```
2026-11-09T14:58:48.311Z 0704c04f sending request 
2026-11-09T14:58:48.314Z 9f12b4c1 sending request 
2026-11-09T14:58:48.319Z 7fb13be6 sending request 
2026-11-09T14:58:48.321Z 999c19fd sending request 
2026-11-09T14:58:48.323Z 04d36844 sending request 
2026-11-09T14:58:53.792Z 7fb13be6 succeeded
2026-11-09T14:58:53.996Z 9f12b4c1 succeeded
2026-11-09T14:58:54.682Z 999c19fd succeeded
2026-11-09T14:58:54.890Z 04d36844 succeeded
2026-11-09T14:58:55.671Z 0704c04f succeeded
2026-11-09T14:58:55.675Z 69cff20c sending request 
2026-11-09T14:58:55.676Z 75e8acea sending request 
2026-11-09T14:58:55.678Z 0f502b48 sending request 
2026-11-09T14:58:55.685Z a242d5e3 sending request 
2026-11-09T14:58:55.688Z a1fb478f sending request 
2026-11-09T14:59:01.107Z a1fb478f succeeded
2026-11-09T14:59:01.855Z 69cff20c succeeded
2026-11-09T14:59:02.067Z 75e8acea succeeded
2026-11-09T14:59:02.712Z 0f502b48 succeeded
2026-11-09T14:59:03.583Z a242d5e3 succeeded
```

No WARN or ERROR in the Canton log during the upload window other than the expected `PERMISSION_DENIED` for
`sv4_validator_user` (sv4Validator polls for its user, which the sv4 SvApp creates later in its init):

```
zcat log/11299/logs-simtime-5/canton-simtime.clog.gz | grep -a -E '"@timestamp":"2026-11-09T1(4:58:(4[89]|5)|4:59|5:00:0)' | grep -a -E '"level":"(WARN|ERROR)"' | grep -a -v -c "UserNotFound(sv4_validator_user-eed41b51)"
```
```
0
```

## 5. Shard load during the uploads (context, not shown to be the cause)

The simulated clock is polled through `IdentityInitializationService/CurrentTime` on one participant. The poll rate
was about 30/s while sv1's uploads were fast (14:43) and about 330/s while sv4's were slow (14:59). The callers are
local Canton gRPC clients, not the splice apps; whether this load slows the DAR uploads was not established.

```
zcat log/11299/logs-simtime-5/canton-simtime.clog.gz | grep -a -F 'IdentityInitializationService/CurrentTime by' | grep -a -F 'received a message' | awk '{t=substr($0,26,6); p="?"; if (match($0,/participant=[a-zA-Z0-9]+/)) p=substr($0,RSTART+12,RLENGTH-12); c[t" "p]++} END{for(k in c) print k, c[k]}' | sort
```
```
T14:42 sv1Participant 799
T14:43 sv1Participant 1773
T14:44 sv1Participant 4905
T14:45 sv1Participant 15370
T14:46 sv1Participant 16398
T14:47 sv1Participant 16048
T14:48 sv1Participant 15925
T14:49 sv1Participant 16452
T14:50 sv1Participant 9728
T14:51 sv1Participant 14916
T14:52 sv1Participant 16127
T14:53 sv1Participant 16861
T14:54 sv1Participant 16924
T14:55 sv1Participant 16775
T14:56 sv1Participant 4378
T14:56 sv2Participant 3063
T14:57 sv2Participant 7504
T14:58 sv2Participant 14818
T14:59 sv2Participant 19857
T15:00 sv2Participant 15258
T15:01 sv2Participant 21083
T15:02 sv2Participant 21110
T15:03 sv1Participant 8838
T15:03 sv2Participant 3870
T15:04 sv1Participant 17027
```

## 6. Duplicate check

Family catalogue and index: the only entry for this suite is family U (10907), an ACS import refusal at a later step
of the same test; 11255 (BFT ordering topology growth) needs a sequencer onboarding, which sv4 never reached here.

```
grep -n -i -E 'SvTimeBasedOnboarding|SvOnboardingRequest' ci-triage/evidence/INDEX.tsv ci-triage/known-families.md | cut -c1-160
```
```
ci-triage/known-families.md:478:  #7944, 10-23). First hit 10907 (SvTimeBasedOnboardingIntegrationTest, simtime, main 3568cba969, 10-29).
```

Tracker search, open and closed:

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "SvTimeBasedOnboardingIntegrationTest"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"'
```
```
#10907 open/- 2026-10-29 SvTimeBasedOnboardingIntegrationTest: sv4 ACS import refused, last event is a topology offset (IMPORT_ACS_ERRO
#10320 closed/completed 2026-10-08 SvTimeBasedOnboardingIntegrationTest: Timeout 10 seconds expired, but readers are still active. Shutting down 
#9486 closed/completed 2026-08-05 SvTimeBasedOnboardingIntegrationTest: Check the vote request is not displayed anymore for one week has passed 
#5967 closed/completed 2025-10-17 EventHistorySanityCheckPlugin: finalizationTime is not equal
#1925 closed/not_planned 2025-01-23 sv3 fails to initialize due to missing dso party ID
#1494 closed/completed 2024-12-20 simtime remoteClock failing
```

- 10907: ACS import refused for sv4, a later step; not this.
- 10320: shutdown timeout with active readers; not this.
- 9486: vote request display check after one week; not this.
- 5967: event history sanity check; not this.
- 1925: sv3 missing DSO party id at init; not this.
- 1494: simtime remote clock failure (2024); not this.

No earlier issue with this clue.

## 7. Baseline

Passing runs upload no log artifacts, and none of the other failed simtime shards on main on 2026-11-09 ran this
suite, so there is no second measurement of sv4's init time:

```
for RJ in 42591855865:'simtime (3)' 42608940134:'simtime (4)' 42618450792:'simtime (3)' 42637895044:'simtime (3)' 42639073931:'simtime (4)' 42639148146:'simtime (3)'; do RUN=${RJ%%:*}; export NAME="${RJ#*:}"; id=$(gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?per_page=100" --jq '.jobs[] | select(.name | endswith(" / " + env.NAME)) | "\(.id)"' | head -1); gh api repos/canton-network/splice/actions/jobs/$id/logs | sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' | grep -a -c -E '^\[info\] SvTimeBasedOnboardingIntegrationTest:'; done
```
```
0
0
0
0
0
0
```

The number of DARs in the repo keeps growing, which plausibly lengthens every joining SV's upload; the number a
joining SV uploads at earlier shas was not measured.

```
for ref in dbcb0c14ca $(git rev-list -1 --before=2026-10-01 origin/main) $(git rev-list -1 --before=2026-07-01 origin/main); do echo "$(git log -1 --format=%ad --date=short $ref) dars=$(git ls-tree -r --name-only $ref daml/dars | grep -c '\.dar$')"; done
```
```
2026-11-09 dars=246
2026-10-01 dars=229
2026-07-01 dars=201
```

## 8. Repeated uploads: the latest version of every package goes up twice

Splice already uploads 5 DARs in parallel and skips DARs the participant has, but only against the list fetched
before the batch (dbcb0c14ca, `ParticipantAdminDarsConnection.scala`):

```
$ git show dbcb0c14ca:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/ParticipantAdminDarsConnection.scala | sed -n '318,331p'
  private def uploadDarsLocally(
      dars: Seq[UploadablePackage],
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      existingDars <- listDars().map(_.map(_.mainPackageId))
      darsToUploads = dars.filterNot(dar => existingDars.contains(dar.packageId))
      _ <- MonadUtil.parTraverseWithLimit(PositiveInt.tryCreate(5))(darsToUploads)(
        uploadDar(_, retryFor)
      )
    } yield {}
  }
```

The list contains the latest version of each package twice: `PackageResource.all = latest +: others`, `latest` is
the `*_current` resource and `others` also holds the same version under its versioned path. `DarResource` is a case
class with the path as a field, so the `.distinct` in `getRequiredPackageVersions` keeps both:

```
$ git show dbcb0c14ca:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/DarResource.scala | sed -n '14,19p;36p'
final case class DarResource(
    path: String,
    packageId: String,
    metadata: PackageMetadata,
    dependencyPackageIds: Set[String],
)
  def all = latest +: others
$ git show dbcb0c14ca:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/DarResources.scala | grep -n -A3 -E 'val wallet_(current|0_1_26) = DarResource'
6833:  lazy val wallet_0_1_26 = DarResource(
6834-    "splice-wallet-0.1.26.dar",
6835-    "5838793a7f11fbc16159065094823fb662120184ede48922dff092958dae6857",
--
6898:  lazy val wallet_current = DarResource(
6899-    "splice-wallet-current.dar",
6900-    "5838793a7f11fbc16159065094823fb662120184ede48922dff092958dae6857",
$ git show dbcb0c14ca:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/DarResources.scala | sed -n '6963,6965p;6987,6995p'
  val wallet = PackageResource(
    wallet_current,
    wallet_0_1_20,
      wallet_0_1_20,
      wallet_0_1_21,
      wallet_0_1_22,
      wallet_0_1_23,
      wallet_0_1_24,
      wallet_0_1_25,
      wallet_0_1_26,
    ),
  )
```

Every participant of the shard uploads about 22 DARs twice (sv4: 87 uploads of 65 distinct DARs in its SvApp init,
plus one `System package` upload at Canton startup 14:33:27; the repeated ones
are exactly the latest version of each package, e.g. `splice-wallet-0.1.26` at 14:58:48.440 and 14:58:55.885):

```
$ zcat log/11299/logs-simtime-5/canton-simtime.clog.gz | grep -a 'Processing package upload of' | sed -E 's/.*Processing package upload of [0-9]+ packages from ([^ ]+) for.*participant=([a-zA-Z0-9]+).*/\2 \1/' | awk '{n[$1]++; k=$1" "$2; if(c[k]++) d[$1]++} END{for(p in n) print p, "uploads", n[p], "repeats", d[p]+0}' | grep -a Participant
sv3Participant uploads 87 repeats 22
sv2Participant uploads 87 repeats 22
bobParticipant uploads 80 repeats 21
sv1Participant uploads 88 repeats 22
splitwellParticipant uploads 81 repeats 21
sv4Participant uploads 87 repeats 22
aliceParticipant uploads 80 repeats 21
```

Each DAR carries all its dependencies (31 to 56 packages), and the participant processes and stores all of them on
every upload: sv4Participant logged 3682 `Storing package` lines for 111 distinct packages. Upload time follows the
DAR's package count and the load, not the number of new packages: on sv1Participant (14:43) the small token
standard DARs (31 to 34 packages, nearly all dependencies already stored) took 0.4 to 0.7 s, the 43 to 55 package wallet DARs 3.5 to 4.3 s; the same DARs took
1.4 to 3.1 s and 5.4 to 7.2 s on sv4Participant (14:59), mean 3.49 s vs 1.31 s. Which step costs (decode, validation,
store) was not checked in the Canton 3.7.2 source.

```
$ zcat log/11299/logs-simtime-5/canton-simtime.clog.gz | grep -a 'participant=sv4Participant' | grep -a 'Storing package' | sed -E 's/.*Storing package ([0-9a-f]+) .*/\1/' | awk '{n++; c[$1]++} END{d=0; for(k in c) d++; print "store calls", n, "distinct packages", d}'
store calls 3682 distinct packages 111
```

Per upload, `Processing package upload` to `Managed to upload` by submissionId (script inline, see chat 2026-11-09):

```
== sv1Participant (upload index: start, seconds, packages, dar)
0 14:43:14.220000 3.5 43 splice-wallet-0.1.20
4 14:43:14.291000 4.3 55 splice-wallet-0.1.26
50 14:43:39.441000 0.47 31 splice-api-token-holding-v1-1.0.0
85 14:43:45.259000 0.61 33 splice-api-reward-assignment-v1-1.0.0
86 14:43:45.259000 0.65 33 splice-api-reward-assignment-v1-1.0.0
uploads 88 mean s 1.31
== sv4Participant (upload index: start, seconds, packages, dar)
0 14:58:48.378000 5.61 43 splice-wallet-0.1.20
4 14:58:48.440000 7.22 55 splice-wallet-0.1.26
50 14:59:44.801000 1.4 31 splice-api-token-holding-v1-1.0.0
85 15:00:09.051000 1.64 33 splice-api-reward-assignment-v1-1.0.0
86 15:00:09.053000 1.65 33 splice-api-reward-assignment-v1-1.0.0
uploads 87 mean s 3.49
```

Deduplicating the upload list by package id would save 22 of 87 uploads (about a quarter) in every SV and validator
init. More parallelism is unlikely to help on CI: all participants share one Canton JVM, and the same uploads ran 2.7
times slower on sv4 than on sv1 in the same shard, i.e. they are bound by the load of that process.

## Verdict

New, first hit. Test flake: the clue gives sv4 60 s from `start()` to its `SvOnboardingRequest`, but sv4's SvApp init
needed 90 s, 81 s of it uploading 87 DARs (5 parallel, about 6 s per batch). sv2 and sv3 needed 67 to 69 s for the
same uploads during setup, so the 60 s budget is below what a joining SV's init takes on this shard even without a
slowdown. Fixes, in the order the user chose on 2026-11-09 (section 8; the user dropped a Canton-side suggestion to skip
already-stored dependency packages, 3682 stores for 111 distinct packages on sv4):
1. splice: deduplicate the DAR upload list by package id (`uploadDarsLocally` or
   `DarResourcesUtil.getRequiredPackageVersions`); 22 of 87 uploads are repeats in every SV and validator init.
2. test: raise the timeout of the eventually at SvTimeBasedOnboardingIntegrationTest.scala:72 to cover the init, e.g.
   `timeUntilSuccess = 3.minutes` (the setup's `waitForInitialization` allows 5 min). Fix 1 alone would not have saved
   this run (65 uploads at this rate take about a minute). `waitForInitialization` on sv4 cannot be used because sv4
   cannot finish onboarding while sv2's and sv3's onboarding triggers are paused.
No clear owner (the timeout comes from the 2024 import); fix agent.

Not verified: why the uploads were 2.6 times slower for sv4 than for sv1 earlier in the shard (the higher sim-clock
poll rate is a candidate, section 5); how long this step takes on passing runs (no artifacts).
