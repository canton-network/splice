# 11379 - deploy_lsu_operator (ciperiodic-lsu): re-onboarded SV after the LSU never initializes, its SV app waits on the uninitialized pre-LSU (legacy) sequencer

Verdict: new, splice SV app (or cluster config), not the 11363 Canton P2P mechanism. The SV in namespace `sv`
(DA-Helm-Test-Node) re-joins after the LSU with new keys. Its SV app config still lists the pre-LSU synchronizer nodes
(`global-domain-0-*`) as `local-synchronizer-nodes.legacy`. A node that joins after the LSU never initializes those, but
`SynchronizerNodeReconciler.buildPhysicalSynchronizers` asks every legacy sequencer for its physical synchronizer id.
`global-domain-0-sequencer` answers without one, the reconciler retries 120 times (about 7 min) and gives up, and
`JoiningNodeInitializer` fails before the sponsor is ever asked to onboard the new (`global-domain-1`) sequencer.
The same pod failed three times (01:24:13, 01:32:29, then a shutdown at 01:38:43). The pod started at 01:39:05 had no
`legacy` block and got past this step within 1.5 s.

## 0. Issue and job

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11379 --jq '.title, .body'   (2026-11-12, before access was removed)
Pipeline 88832 : workflow deploy_lsu_operator Failed :fire:
[CircleCI Job](https://app.circleci.com/pipelines/github/DACH-NY/canton-network-internal/88832/workflows/cfd0978d-4843-4ebc-b6d9-1de15d542599/jobs/531400/parallel-runs/0).
Branch: [build/ciperiodic-lsu](https://github.com/DACH-NY/canton-network-internal/tree/build/ciperiodic-lsu)
Workflow: deploy_lsu_operator
Commit: [35053e4](https://github.com/DACH-NY/canton-network-internal/commit/) Update config to retire the old physical synchronizer on ciperiodic
Author: DA Automation
```
Comment by martinflorian-da (2026-11-12 09:08): "During SV reonboarding after the LSU, it looks like it got stuck around
initializing the sequencer. ... SV reonboarding is currently unsupported also for other reasons, so not considering
this a release blocker." (GCP link: project da-cn-ci, cluster cn-ciperiodicnet, namespace sv, container sv-app.)

Logs exported on the host (2026-11-12 15:58) to `ci-triage/export/11379/`:
```
direnv exec . .claude/skills/ci-triage/scripts/export_gcp_logs.py 11379 --project da-cn-ci --cluster cn-ciperiodicnet --start 2026-11-12T00:00:00Z --end 2026-11-12T01:40:00Z --query seq-p2p 'resource.labels.container_name="sequencer" AND jsonPayload.message=~"P2P endpoint|already associated|Dropping network message|Onboarding state transfer|Retrying block transfer|Not enough authenticated|New epoch|authenticat"' --query seq-warn 'resource.labels.container_name="sequencer" AND severity>=WARNING'
direnv exec . .claude/skills/ci-triage/scripts/export_gcp_logs.py 11379 --project da-cn-ci --cluster cn-ciperiodicnet --start 2026-11-12T01:05:00Z --end 2026-11-12T01:40:00Z --query sv-ns 'resource.labels.namespace_name="sv"'
direnv exec . .claude/skills/ci-triage/scripts/export_circleci.py 11379 531400
```
The exports are gitignored and stay on the machine of whoever ran them; rerun the export commands above to recreate them (GCP logs only while Cloud Logging still holds them).

Converted to one TSV line per entry (timestamp, severity, namespace, pod, container, logger, message):
```
E=ci-triage/export/11379
for f in seq-p2p seq-warn sv-ns; do jq -r '.[] | [.timestamp, .severity, .resource.labels.namespace_name, .resource.labels.pod_name, .resource.labels.container_name, (.jsonPayload.logger_name // ""), ((.jsonPayload.message // .textPayload // "") | gsub("\n"; " | "))] | @tsv' $E/gcp/$f.json | gzip > log/11379/$f.tsv.gz; echo "$f $(zcat log/11379/$f.tsv.gz | wc -l)"; done
```
```
seq-p2p 162356
seq-warn 60
sv-ns 75226
```

## 1. The failing CircleCI step

`531400-steps.txt`: job `preflight_sv_check_post_redeploy`, start 2026-11-12T01:12:01.58Z, step 041 `wait for sv-runbook
deployment to finish` failed. Its output (non-repeating lines, ANSI stripped):
```
$ sed -E 's/\x1b\[[0-9;]*m//g' $E/531400-041-wait_for_sv-runbook_deployment_to_finish.txt | grep -v 'no matching resources' | tail -6
Running command with retry (until 'Active'): kubectl get ns sv -o jsonpath='{.status.phase}'
Namespace sv ready, waiting for validator-app
Running command with retry: kubectl wait pod --for=condition=Ready --timeout=3s --namespace sv --selector=app=validator-app
Too many attempts, exiting
Exceeded maximum retries (1 / 0), no more attempts wait for sv-runbook deployment to finish
```
The cluster reported `0.10.2-snapshot.20261111.4339.0.vd356bcf5` (splice d356bcf573, 2026-11-11 18:29) in the same step.

## 2. Not the 11363 mechanism: no endpoint flips, no state transfer on any sequencer

```
$ zcat seq-p2p.tsv.gz | grep -c -E "was previously associated with|already associated with|Starting Onboarding state transfer|Retrying block transfer|Not enough authenticated"
0
```
All five `seq-p2p` patterns were in the export filter, and the cluster logs these Canton lines at INFO (11363's test log has them at INFO too), so a 11363-style flip would have been exported. The new sequencer of namespace `sv` never started a state transfer because it was never onboarded (section 4).

Side fact, not the cause: the peers drop messages to the OLD sequencer of the re-onboarded SV, still in the ordering
topology, from 00:06 on (`SEQ::DA-Helm-Test-Node::12203b52...`, 131085 lines over the four other SVs and both physical
synchronizers). The new SV app tries to remove it but cannot yet sign (not yet a namespace owner):
```
$ zcat sv-ns.tsv.gz | awk -F'\t' '$5=="sv-app" && $1>="2026-11-12T01:15:54.9" && $1<="2026-11-12T01:15:55.1"' | cut -f1,7 | grep -E 'Planning to remove sequencers SEQ|failed with a retryable error.*Remove sequencer' | cut -c1-200
2026-11-12T01:15:54.907Z	Planning to remove sequencers SEQ::DA-Helm-Test-Node::12203b52f816... to match DsoRulesWithSvNodeStates( |   domain = global-domain::122081711903..., |   dsoRulesCid = 009836f
2026-11-12T01:15:55.035Z	The operation 'Remove sequencer SEQ::DA-Helm-Test-Node::12203b52f816...' failed with a retryable error (full stack trace omitted): | category=Some(InvalidGivenCurrentSystemSta
```

## 3. The SV app config: pre-LSU nodes as `legacy`, post-LSU nodes as `current`

```
$ zcat sv-ns.tsv.gz | awk -F'\t' '$5=="sv-app" && $7 ~ /^SpliceEnvironment with config/' | cut -f7 | sed 's/ | /\n/g' | grep -n -A3 -E '^\s*(legacy|current) \{' | grep -E 'legacy|current|address'   (first of the pod's config dumps)
415:            current {
418:                        address=global-domain-1-mediator
527:            legacy {
530:                        address=global-domain-0-mediator
```
Per start of the SV app (pod, number of `legacy {` blocks in the config dump):
```
$ zcat sv-ns.tsv.gz | awk -F'\t' '$5=="sv-app" && $7 ~ /^SpliceEnvironment with config/ {n=split($7,a," \\| "); leg=0; for(i=1;i<=n;i++) if(a[i] ~ /^ *legacy \{/) leg++; print $1, $4, "legacy-blocks=" leg}'
2026-11-12T01:13:30.903Z sv-app-ff4874674-s8qzt legacy-blocks=1
2026-11-12T01:24:43.549Z sv-app-ff4874674-s8qzt legacy-blocks=1
2026-11-12T01:33:13.057Z sv-app-ff4874674-s8qzt legacy-blocks=1
2026-11-12T01:39:05.572Z sv-app-5866694fc8-hsnqm legacy-blocks=0
```

## 4. The reconciler waits on the legacy sequencer until init fails

sv-app, selected lines (all attempts):
```
$ zcat sv-ns.tsv.gz | awk -F'\t' '$5=="sv-app"' | cut -f1,2,6,7 | grep -E 'Onboarding sequencer|Got .sequencer status.: Left|Total maximum number of retries 120 exceeded|JoiningNodeInitializer joining Dso with key failed|ERROR.*Initialization failed' | awk -F'\t' '$1>="2026-11-12T01:16:52"' | cut -c1-250
2026-11-12T01:16:52.411Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:17:00.689Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:17:00.689Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Onboarding sequencer
2026-11-12T01:24:13.602Z	INFO	o.l.s.s.o.SynchronizerNodeReconciler:SV=sv	The operation 'setting domain config for DA-Helm-Test-Node::1220a0872d47...' has failed with an exception. Total maximum number of retries 120 exceeded. Giving up. 
2026-11-12T01:24:13.603Z	INFO	o.l.splice.sv.SvApp:SV=sv	sv app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-12T01:24:13.606Z	ERROR	o.l.splice.sv.SvApp:SV=sv	sv app initialization: Initialization failed
2026-11-12T01:24:13.607019687Z			Caused by: java.lang.RuntimeException: sv app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-12T01:25:28.252Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:25:28.459Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:25:28.459Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Onboarding sequencer
2026-11-12T01:32:29.571Z	INFO	o.l.s.s.o.SynchronizerNodeReconciler:SV=sv	The operation 'setting domain config for DA-Helm-Test-Node::1220a0872d47...' has failed with an exception. Total maximum number of retries 120 exceeded. Giving up. 
2026-11-12T01:32:29.572Z	INFO	o.l.splice.sv.SvApp:SV=sv	sv app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-12T01:32:29.575Z	ERROR	o.l.splice.sv.SvApp:SV=sv	sv app initialization: Initialization failed
2026-11-12T01:32:29.575776672Z			Caused by: java.lang.RuntimeException: sv app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-12T01:34:00.231Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:34:00.418Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:34:00.418Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Onboarding sequencer
2026-11-12T01:38:43.474Z	INFO	o.l.splice.sv.SvApp:SV=sv	sv app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-12T01:39:51.091Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:39:51.261Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Got 'sequencer status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.3-snapshot.20261111.20904.0.vbe797d9d))
2026-11-12T01:39:51.261Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Onboarding sequencer
2026-11-12T01:39:52.463Z	INFO	o.l.s.s.LocalSynchronizerNode:SV=sv	Onboarding sequencer SEQ::DA-Helm-Test-Node::1220203bb68e... through sponsoring SV
```
(01:38:43 is followed by `Ignoring initialization failure, we are actually shutting down`: the pod was replaced.)

Each retry of the reconciler fails on the legacy sequencer:
```
$ zcat sv-ns.tsv.gz | awk -F'\t' '$5=="sv-app" && $7 ~ /setting domain config.*failed with a retryable error.*Sequencer does not have a synchronizer ID/ {if(!f)f=$1; l=$1; c++} END{print "first",f,"last",l,"count",c}'
first 2026-11-12T01:17:01.035Z last 2026-11-12T01:34:57.493Z count 259
$ zcat sv-ns.tsv.gz | awk -F'\t' '$5=="sv-app" && $1>="2026-11-12T01:17:00" && $1<="2026-11-12T01:24:14" && $7 ~ /SequencerStatus to global-domain-[01]-sequencer.*sending request/' | grep -o 'to global-domain-[01]-sequencer' | sort | uniq -c
    121 to global-domain-0-sequencer
     36 to global-domain-1-sequencer
```
Per pod:
```
$ for p in 'Setting domain node config' 'Onboarding sequencer SEQ::' 'Sequencer does not have a synchronizer ID'; do echo "== $p"; zcat sv-ns.tsv.gz | awk -F'\t' -v p="$p" '$5=="sv-app" && index($7,p) {c[$4]++} END{for(k in c) print c[k], k}'; done
== Setting domain node config
1 sv-app-5866694fc8-hsnqm
== Onboarding sequencer SEQ::
1 sv-app-5866694fc8-hsnqm
== Sequencer does not have a synchronizer ID
261 sv-app-ff4874674-s8qzt
```
(261 counts every line with the message, 259 only the `failed with a retryable error` lines.)

The new sequencer (`global-domain-1-sequencer` in `sv`) only answered status requests during the first attempt; it was
never asked to initialize:
```
$ zcat sv-ns.tsv.gz | awk -F'\t' '$4 ~ /global-domain-1-sequencer/ && $5=="sequencer" && $1>="2026-11-12T01:17:00" && $1<"2026-11-12T01:24:14"' | cut -f7 | cut -c1-60 | sort | uniq -c | sort -rn | head
    180 Request com.digitalasset.canton.admin.sequencer.v30.Sequence
     13 Request grpc.health.v1.Health/Watch by grpc:10.52.22.6: send
     13 Request grpc.health.v1.Health/Watch by grpc:10.52.22.6: rece
     13 Request grpc.health.v1.Health/Watch by grpc:10.52.22.6: canc
      7 Here is our list of the 15 most expensive database queries f
```

## 5. Code at the version that ran (splice d356bcf573)

```
$ git log -1 --format='%H %cd %s' d356bcf5
d356bcf573bd8c9e635ffbcddc9187b40d5af11a Wed Nov 11 18:29:56 2026 +0000 Wait 30 s for the redirect back to splitwell after payment-accept (#8423)
$ git show d356bcf5:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala | sed -n 203,206p
    val legacyEntryFuture =
      synchronizerNodes.legacy.traverse { legacyNode =>
        buildNodeConfig(legacyNode)
      }
$ ... | sed -n 270,273p      (buildNodeConfig without serialOverride)
    val serialFuture = serialOverride match {
      case Some(serialOverride) => Future.successful(serialOverride)
      case None => node.sequencerAdminConnection.getPhysicalSynchronizerId().map(_.serial)
    }
$ git show d356bcf5:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/SequencerAdminConnection.scala | sed -n 166,179p
  def getPhysicalSynchronizerId()(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] = {
    runCmd(
      SequencerAdminCommands.Health.SequencerStatusCommand()
    ).map(
      _.successOption
        .map(_.synchronizerId)
        .getOrElse(
          throw Status.UNAVAILABLE
            .withDescription("Sequencer does not have a synchronizer ID in its status response")
            .asRuntimeException()
        )
    )
  }
```
The successor entry, by contrast, tolerates an uninitialized node (`isNodeInitialized()`, `case false => None`, lines
213-235). The whole `setConfigIfRequired` runs under `retryProvider.retry(RetryFor.WaitingOnInitDependency,
"set_domain_config", s"setting domain config for $svParty", ...)` (lines 176-182).
```
$ git log --oneline d356bcf5..origin/main -- apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala
(no output)
$ git blame -L '/val legacyEntryFuture/,+4' d356bcf5 -- apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala | cut -c1-40
9eea7b2cab0 (Nicu Reut 2026-09-17 11:28:15 +0200 203)
...
```
(9eea7b2cab, #5932 "No longer set the deprecated sequencer connections in the dso rules".) Unchanged on main.

Why the reconciler blocks the sequencer onboarding: `onboardLocalSequencerIfRequired` runs `preInit()` before
`onboardLocalSequencer`, and the joining initializer passes the reconciler (with status `Onboarding`) as `preInit`:
```
$ git show d356bcf5:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/LocalSynchronizerNode.scala | sed -n 428,433p
      .flatMap {
        case Left(NodeStatus.NotInitialized(_, _, _)) =>
          logger.info("Onboarding sequencer")
          svConnection
            .flatMap(svConnection => preInit().flatMap(_ => onboardLocalSequencer(svConnection)))
        case Right(NodeStatus.Success(s)) =>
$ git show d356bcf5:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/joining/JoiningNodeInitializer.scala | sed -n 501,512p
            // Finally, fully onboard the sequencer and mediator
            physicalSynchronizerId <-
              currentNode.onboardLocalSequencerIfRequired(
                svConnection.map(_._2),
                // Add the new local domain node to the DSO rules with an "onboarding" status
                // This triggers automation in other SV apps, that's why we make sure the sequencer is known first
                preInit = () =>
                  synchronizerNodeReconciler.reconcileSynchronizerNodeConfigIfRequired(
                    synchronizerNodeService.nodes,
                    decentralizedSynchronizer,
                    Onboarding(participantReportedPSid.serial),
                  ),
```
The `Onboarding sequencer SEQ::... through sponsoring SV` line (LocalSynchronizerNode.scala:443, in
`onboardLocalSequencer`) appears only in the pod without the legacy block (section 4).

```
$ git show d356bcf5:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryFor.scala | sed -n 40,41p
  val WaitingOnInitDependency: RetryFor = RetryFor(
    maxRetries = 120,
```
All seven permalinks return HTTP 200 (`curl -s -o /dev/null -w '%{http_code}'`, 2026-11-12).

Permalinks used in the issue comment (all at d356bcf573bd8c9e635ffbcddc9187b40d5af11a, public repo canton-network/splice):
LocalSynchronizerNode.scala#L428-L433, JoiningNodeInitializer.scala#L501-L512, SynchronizerNodeReconciler.scala#L176-L182,
#L203-L206, #L213-L235, #L270-L273, SequencerAdminConnection.scala#L166-L179.

## Verdict

- Cause: for an SV that joins after an LSU, the configured legacy synchronizer nodes are never initialized, but the SV
  app's synchronizer node reconciler requires every legacy sequencer to report its physical synchronizer id. It
  retries for about 7 min, then initialization fails, three times in a row on pod sv-app-ff4874674-s8qzt. The new
  sequencer is never onboarded, so no validator-app appears and the CI wait fails.
- Confirmation of the dependency: the next pod (sv-app-5866694fc8-hsnqm, 01:39:05) had no `legacy` block and set the
  node config and requested sequencer onboarding through the sponsor at 01:39:51-01:39:52. The export ends at 01:40,
  so whether that onboarding completed is not in the logs.
- Fix options (owner to decide): in `SynchronizerNodeReconciler.buildPhysicalSynchronizers`, treat an uninitialized
  legacy (and additional-legacy) node like an uninitialized successor (skip it, keep the existing entries); or do not
  configure `legacy` nodes for an SV that onboards after the LSU (deployment config, canton-network-internal /
  cluster/pulumi). martinflorian-da: SV re-onboarding after an LSU is not supported yet for other reasons too.
- Not the 11363 Canton P2P bug: no endpoint flips, no state transfer (section 2).
- Not verified: who or what replaced the pod at 01:38:43 without the legacy block (the issue's commit is "Update config
  to retire the old physical synchronizer on ciperiodic", which may be that redeploy); whether the 01:39 onboarding
  succeeded; why the old sequencer of this SV stayed in the ordering topology from 00:06 (it does not block init).
