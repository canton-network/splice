# 10144 - run 35076492327 resource-intensive (1): INTERNAL on GetPreferredPackages while the SV app uploads DARs: package metadata view seen mid-update

Post-merge CI on main, sha f1ee318e39 "Don't wait forever on a non-active psid in
ensureSynchronizerRegisteredAndConnected (#7311)", 2026-09-16T08:55Z. Canton runtime
3.6.0-snapshot.20260910.20260.0.v90621933. Two failed jobs in the run (ui_tests and this one); Raymond's
refs 10144 and 10145 map to the two jobs, which ref is which is not known. This packet covers job
104730519175 `ci / scala_test_resource_intensive / resource-intensive (1)` only. Commands verified against
the artifacts in log/35076492327-resource-intensive-1/.

## Categorization

- Test(s) affected: none asserted-failed. Both suites in the shard (SvReonboardingIntegrationTest,
  BootstrapPackageConfigIntegrationTest) passed. The flagged lines were emitted during
  BootstrapPackageConfigIntegrationTest "Bootstrap with specific versions and then upgrade to latest".
- Failure type: checkErrors (3 un-allowlisted lines in canton_network_test.clog: 1 ERROR + 2 WARN, all
  from the sv1 SV app, all one gRPC call).
- Component: Canton participant, ledger API `InteractiveSubmissionService/GetPreferredPackages`
  (`PackagePreferenceBackend`) reading the in-memory `PackageMetadataView` while a concurrent DAR upload
  on the same participant is merging that DAR's packages into the view one package at a time
  (`MutablePackageMetadataViewImpl.updateMany`). The view is momentarily not closed under dependencies
  and the dependency walk throws `IllegalStateException("Missing package-id ... in package metadata view")`,
  surfaced to the client as a redacted INTERNAL.
- Flake vs real: flake for splice (1 failure in 745 GetPreferredPackages calls in the run, the trigger
  retried after 1 s and succeeded); a real, known Canton race. The server-side ERROR is already
  allowlisted since #6356 (2026-07-09, cn-test-failures#9136); the client-side lines are not, because the
  ledger API redacts INTERNAL so the client sees "An error occurred. Please contact the operator", which
  the existing pattern cannot match.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35076492327 --repo canton-network/splice -n logs-resource-intensive-1 -D dl
cd dl
# canton_before_shutdown.clog.gz = node log DURING the run; canton_network_test.clog.gz = harness + splice apps
# job-104730519175.log = full GHA job console (gh api repos/canton-network/splice/actions/jobs/104730519175/logs)
```

## 1. Failed job, no test failed

```
grep -anE 'lines with ignored|Found problems|lines with problems|\(checkErrors\) log|Process completed with exit code' \
  job-104730519175.log | cut -c1-160
```
```
11808:2026-09-16T09:11:33.6415714Z Total: 197 lines with ignored entries.
11865:2026-09-16T09:11:36.5924838Z Total: 16 lines with ignored entries.
11934:2026-09-16T09:11:37.6105313Z Total: 44 lines with ignored entries.
12055:2026-09-16T09:11:38.6431038Z Total: 96 lines with ignored entries.
12095:2026-09-16T09:11:41.6482441Z Total: 15 lines with ignored entries.
12097:2026-09-16T09:11:41.6482988Z Found problems in log/canton_network_test.clog:
12101:2026-09-16T09:11:41.6521104Z Total: 3 lines with problems.
12122:2026-09-16T09:11:41.6532569Z [error] (checkErrors) log/canton_network_test.clog contains problems.
12127:2026-09-16T09:11:42.6280251Z ##[error]Process completed with exit code 1.
```

```
zcat canton_network_test.clog.gz | grep -a "Starting test suite" | grep -aoE "suite '[^']+'" | sort | uniq -c
echo succeeded $(zcat canton_network_test.clog.gz | grep -ac 'Test succeeded:')
echo failed $(zcat canton_network_test.clog.gz | grep -ac 'Test failed:')
zcat canton_network_test.clog.gz | grep -aE "Starting test suite|Test succeeded|Test failed" \
  | grep -aE '"@timestamp":"2026-09-16T09:(0[5-9]|1[0-1])' | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,170}' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | cut -c1-240
```
```
      1 suite 'BootstrapPackageConfigIntegrationTest'
      1 suite 'SvReonboardingIntegrationTest'
succeeded 2
failed 0
"@timestamp":"2026-09-16T09:07:26.626Z" "message":"Test succeeded: 'SvReonboardingIntegrationTest/reonboard SV with new party id and recover amulet via new regular validator'
"@timestamp":"2026-09-16T09:07:29.024Z" "message":"Starting test suite 'BootstrapPackageConfigIntegrationTest'...
"@timestamp":"2026-09-16T09:11:17.374Z" "message":"Test succeeded: 'BootstrapPackageConfigIntegrationTest/Bootstrap with specific versions and then upgrade to latest'
```

## 2. The three flagged lines: one call from the sv1 SV app

```
n=$(grep -an 'Found problems' job-104730519175.log | head -1 | cut -d: -f1)
sed -n "$((n+1)),$((n+3))p" job-104730519175.log | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | cut -c1-1200
```
(Each line below is the start of the corresponding console line; the gRPC trailer, thread_name, span and
stack_trace fields are elided by hand at the `...` marks.)
```
2026-09-16T09:11:41.6487949Z ***"@timestamp":"2026-09-16T09:10:25.971Z","message":"Request (tid:<HASH>) com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages to 127.0.0.1:5101: failed with INTERNAL/An error occurred. Please contact the operator and inquire about the request <no-correlation-id> with tid <no-tid>\n  Trailers: Metadata(content-type=application/grpc,grpc-status-details-bin=CA0Sck...)","logger_name":"o.l.s.a.a.c.ApiClientRequestLogger:BootstrapPackageConfigIntegrationTest/config=c5943284/SV=sv1","thread_name":"BootstrapPackageConfigIntegrationTest-c5943284-env-ec-1624","level":"ERROR",...,"span-name":"CreateBootstrapExternalPartyConfigStateInstructionTrigger-work"***
2026-09-16T09:11:41.6498911Z ***"@timestamp":"2026-09-16T09:10:25.972Z","message":"The operation 'Get the supported package version for packageRequirements List((splice-dso-governance,List(DSO-c5943284-c5943284::1220049f8580..., digital-asset-2-c5943284::12207e005015...))) on synchronizer global-domain::1220049f8580... with vetting time 2026-09-16T09:10:25.840593Z' failed with a non-retryable error, not retrying:\ncategory=None\nstatusCode=INTERNAL\ndescription=An error occurred. Please contact the operator and inquire about the request <no-correlation-id> with tid <no-tid>","logger_name":"o.l.s.e.BaseLedgerConnection:BootstrapPackageConfigIntegrationTest/config=c5943284/SV=sv1/roConnClient=SV1Initializer",...,"level":"WARN",...
2026-09-16T09:11:41.6514358Z ***"@timestamp":"2026-09-16T09:10:25.972Z","message":"The operation 'pollingTriggerTask' failed with a non-transient error, restarting after 1 second:\ncategory=None\nstatusCode=INTERNAL\ndescription=An error occurred. Please contact the operator and inquire about the request <no-correlation-id> with tid <no-tid>","logger_name":"o.l.s.s.a.c.CreateBootstrapExternalPartyConfigStateInstructionTrigger:BootstrapPackageConfigIntegrationTest/config=c5943284/SV=sv1",...,"level":"WARN",...
```

App: `SV=sv1`. Trigger: `CreateBootstrapExternalPartyConfigStateInstructionTrigger` (span
`CreateBootstrapExternalPartyConfigStateInstructionTrigger-work`), through the read-only connection
`roConnClient=SV1Initializer`. Vetting time passed by splice: 2026-09-16T09:10:25.840593Z (= the trigger's
`context.clock.now`, see section 10). The client tid:

```
zcat canton_network_test.clog.gz | grep -a 'GetPreferredPackages to 127.0.0.1:5101: failed with INTERNAL' | grep -aoE 'tid:[0-9a-f]+'
```
```
tid:ce37db349b622abf86bc319274958b36
```

Port 5101 is sv1Participant's ledger API:

```
cd <splice>; grep -rn '5101' apps/app/src/test/resources --include=*.conf | cut -c1-90 | head -3
zcat canton_before_shutdown.clog.gz | grep -a 'participant=sv1Participant' | grep -a 'Listening on 127.0.0.1:5101' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,60}' | paste -sd' '
```
```
apps/app/src/test/resources/simple-topology-canton.conf:8:  ledger-api.port = 5101
apps/app/src/test/resources/include/svs/_sv.conf:14:    ledger-api.client-config.port = 5101
apps/app/src/test/resources/include/scans/sv1-scan.conf:6:    ledger-api.client-config.port = 5101
"@timestamp":"2026-09-16T09:00:14.765Z" "message":"Listening on 127.0.0.1:5101 over plain text with LedgerApiKeepAliv
```

## 3. Canton runtime version

```
zcat canton_before_shutdown.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
cd <splice>; git show f1ee318e39:nix/canton-sources.json | grep -m1 '"version"'; cat canton/VERSION
```
```
Canton version 3.6.0-snapshot.20260910.20260.0.v90621933
  "version": "3.6.0-snapshot.20260910.20260.0.v90621933",
3.5.7-SNAPSHOT
```

The vendored `canton/` tree (3.5.7-SNAPSHOT) is NOT what ran. Section 7 verifies the mechanism against the
real 3.6.0-snapshot.20260910.20260.0.v90621933 jar (downloaded from canton.io and disassembled with javap);
vendored citations are marked with their version and only used where the runtime LineNumberTable agrees.

## 4. Server side: the same tid on sv1Participant is "Missing package-id ... in package metadata view"

```
zcat canton_before_shutdown.clog.gz | grep -ac 'ce37db349b622abf86bc319274958b36'
zcat canton_before_shutdown.clog.gz | grep -a 'ce37db349b622abf86bc319274958b36' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,260}|"logger_name":"[^"]{0,120}|"level":"[A-Z]+"' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | cut -c1-330
```
```
9
"@timestamp":"2026-09-16T09:10:25.870Z" "message":"Request com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages by grpc:/127.0.0.1:56298: auth claims: Claims(List(ClaimPublic, ClaimAdmin, ClaimActAsParty(DSO-c5943284-c5943284::<HASH> "logger_name":"c.d.c.l.a.ApiRequestLogger:participant=sv1Participant "level":"DEBUG"
"@timestamp":"2026-09-16T09:10:25.872Z" "message":"Request com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages by grpc:/127.0.0.1:56298: received a message GetPreferredPackagesRequest(\n  PackageVettingRequirement(\n    Vector(DSO-c5943284-c5943284::<HASH> "logger_name":"c.d.c.l.a.ApiRequestLogger:participant=sv1Participant "level":"DEBUG"
"@timestamp":"2026-09-16T09:10:25.872Z" "message":"Routing state contains connected synchronizers global-domain::1220049f8580...::36-0 and topology global-domain::1220049f8580...::36-0 at 2026-09-16T09:10:21.781842Z "logger_name":"c.d.c.p.s.CantonSyncService:participant=sv1Participant "level":"DEBUG"
"@timestamp":"2026-09-16T09:10:25.872Z" "message":"Topology queried for the following synchronizers: Set(global-domain::1220049f8580...::36-0) "logger_name":"c.d.c.p.p.s.r.AdmissibleSynchronizersComputation:participant=sv1Participant "level":"DEBUG"
"@timestamp":"2026-09-16T09:10:25.872Z" "message":"Synchronizers with all submitters: Set(global-domain::1220049f8580...::36-0) "logger_name":"c.d.c.p.p.s.r.AdmissibleSynchronizersComputation:participant=sv1Participant "level":"DEBUG"
"@timestamp":"2026-09-16T09:10:25.872Z" "message":"Synchronizers with all informees: Set(global-domain::1220049f8580...::36-0) "logger_name":"c.d.c.p.p.s.r.AdmissibleSynchronizersComputation:participant=sv1Participant "level":"DEBUG"
"@timestamp":"2026-09-16T09:10:25.873Z" "message":"Checking whether one synchronizer in Set(global-domain::1220049f8580...::36-0) is suitable for submission "logger_name":"c.d.c.p.p.s.r.AdmissibleSynchronizersComputation:participant=sv1Participant "level":"DEBUG"
"@timestamp":"2026-09-16T09:10:25.875Z" "message":"Request com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages by grpc:/127.0.0.1:56298: failed with INTERNAL/Missing package-id <HASH> in package metadata view "logger_name":"c.d.c.l.a.ApiRequestLogger:participant=sv1Participant "level":"ERROR"
"@timestamp":"2026-09-16T09:10:25.971Z" "message":"Request com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages by grpc:/127.0.0.1:56298: completed "logger_name":"c.d.c.l.a.ApiRequestLogger:participant=sv1Participant "level":"DEBUG"
```

It is the only WARN/ERROR on any Canton node in the +-3 s window, and the only GetPreferredPackages failure
in the whole run (745 calls across 7 participants):

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:10:2[3-8]' | grep -acE '"level":"(WARN|ERROR)"'
zcat canton_before_shutdown.clog.gz | grep -a 'ApiRequestLogger:participant=' | grep -a 'GetPreferredPackages' \
  | grep -aE 'failed with|succeeded\(' | grep -aoE 'participant=[A-Za-z0-9]+|failed with [A-Z_]+|succeeded\(OK\)' \
  | paste -sd' ' | sed 's/participant=/\nparticipant=/g' | sort | uniq -c | grep -a participant
```
```
1
     15 participant=aliceParticipant succeeded(OK)
     10 participant=bobParticipant succeeded(OK)
     10 participant=splitwellParticipant succeeded(OK)
      1 participant=sv1Participant failed with INTERNAL
    196 participant=sv1Participant succeeded(OK)
    174 participant=sv2Participant succeeded(OK)
    171 participant=sv3Participant succeeded(OK)
    167 participant=sv4Participant succeeded(OK)
```

The runtime stack trace (in the ERROR line's `stack_trace` field) pins the throw site:

```
zcat canton_before_shutdown.clog.gz | grep -a 'Missing package-id' | grep -a '"level":"ERROR"' \
  | sed 's/\\n\\tat /\n    at /g' | grep -aE '^    at com\.digitalasset' | cut -c1-140
```
```
    at com.digitalasset.canton.store.packagemeta.PackageMetadata.$anonfun$tryGet$1(PackageMetadata.scala:92)
    at com.digitalasset.canton.store.packagemeta.PackageMetadata.tryGet(PackageMetadata.scala:92)
    at com.digitalasset.canton.store.packagemeta.PackageMetadata.go$1(PackageMetadata.scala:51)
    at com.digitalasset.canton.store.packagemeta.PackageMetadata.allDependencySetsRecursively(PackageMetadata.scala:61)
    at com.digitalasset.canton.platform.PackagePreferenceBackend.$anonfun$getPreferredPackages$4(PackagePreferenceBackend.scala:122)
    at com.digitalasset.canton.lifecycle.FutureUnlessShutdownImpl$$anon$5.$anonfun$flatMap$1(FutureUnlessShutdown.scala:363)
```

## 5. The missing package is splice-amulet-name-service 0.1.21, stored on sv1 57 ms before the query

```
PKG=$(zcat canton_before_shutdown.clog.gz | grep -a 'ce37db349b622abf86bc319274958b36' | grep -aoE 'Missing package-id [0-9a-f]+' | awk '{print $3}')
echo $PKG
zcat canton_before_shutdown.clog.gz | grep -a 'participant=sv1Participant' | grep -a "$PKG" | head -1 | grep -aoE '"@timestamp":"[^"]*"'
zcat canton_before_shutdown.clog.gz | grep -a "$PKG" | grep -a 'Storing package' | grep -a 'sv1Participant' \
  | grep -aoE '"@timestamp":"[^"]*"|PackageInfo\([^)]*\)' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -a .
```
```
b342bbbd425902283c20eb5011eeab90cc9f69b4c534b8b757fd51b8ca7be589
"@timestamp":"2026-09-16T09:10:25.818Z"
"@timestamp":"2026-09-16T09:10:25.818Z" PackageInfo(splice-amulet-name-service,0.1.21)
"@timestamp":"2026-09-16T09:10:28.039Z" PackageInfo(splice-amulet-name-service,0.1.21)
```

The 25.818 store is not the ANS-0.1.21 DAR itself (that upload arrives at 26.688 and stores at 28.039); it is
a dependency inside the splice-dso-governance-0.1.26 DAR, whose main package was stored 1 ms earlier:

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:10:25\.[3-9]' \
  | grep -a 'DbDamlPackageStore:participant=sv1Participant' | grep -a 'Storing package' \
  | grep -aoE '"@timestamp":"[^"]*"|Storing package [0-9a-f]{8}[0-9a-f]* PackageInfo\([^)]*\)' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | sed -E 's/package ([0-9a-f]{8})[0-9a-f]+/package \1.../' \
  | grep -a . | grep -avE 'daml-prim|daml-stdlib|ghc-stdlib|splice-api|splice-util' | cut -c1-140
```
```
"@timestamp":"2026-09-16T09:10:25.310Z" Storing package 340afccb... PackageInfo(splice-wallet-payments,0.1.20)
"@timestamp":"2026-09-16T09:10:25.311Z" Storing package 23f47481... PackageInfo(splice-amulet,0.1.20)
"@timestamp":"2026-09-16T09:10:25.817Z" Storing package 45099e95... PackageInfo(splice-dso-governance,0.1.26)
"@timestamp":"2026-09-16T09:10:25.818Z" Storing package 23f47481... PackageInfo(splice-amulet,0.1.20)
"@timestamp":"2026-09-16T09:10:25.818Z" Storing package b342bbbd... PackageInfo(splice-amulet-name-service,0.1.21)
"@timestamp":"2026-09-16T09:10:25.818Z" Storing package 340afccb... PackageInfo(splice-wallet-payments,0.1.20)
```

Among the dso-governance versions sv1 knew at 09:10:25, only 0.1.26 depends on ANS 0.1.21 (0.1.27 bundles
ANS 0.1.22, 0.1.29 bundles ANS 0.1.24), so the dependency walk reached b342 through 45099e95 (dso-governance
0.1.26), which therefore WAS in the snapshot while its dependency was not:

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:10:1[2-6]' \
  | grep -a 'DbDamlPackageStore:participant=sv1Participant' | grep -a 'Storing package' \
  | grep -aoE '"@timestamp":"[^"]*"|PackageInfo\((splice-dso-governance|splice-amulet-name-service),[^)]*\)' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -a 'PackageInfo' | cut -c1-140
```
```
"@timestamp":"2026-09-16T09:10:15.626Z" PackageInfo(splice-dso-governance,0.1.27)
"@timestamp":"2026-09-16T09:10:15.627Z" PackageInfo(splice-amulet-name-service,0.1.22)
"@timestamp":"2026-09-16T09:10:15.971Z" PackageInfo(splice-dso-governance,0.1.29)
"@timestamp":"2026-09-16T09:10:15.972Z" PackageInfo(splice-amulet-name-service,0.1.24)
```

## 6. The 7 ms window: DAR persisted, view being updated, query snapshots the view, upload responds

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:10:25\.[89]' | grep -a 'participant=sv1Participant' \
  | grep -aE 'Storing package 45099e95|Storing package b342bbbd|Managed to upload|GetPreferredPackages by grpc:/127.0.0.1:56298: (received|failed)|UploadDarResponse\(45099e95' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,175}' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' \
  | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | grep -a . | cut -c1-260
```
```
"@timestamp":"2026-09-16T09:10:25.817Z" "message":"Storing package <HASH> PackageInfo(splice-dso-governance,0.1.26)
"@timestamp":"2026-09-16T09:10:25.818Z" "message":"Storing package <HASH> PackageInfo(splice-amulet-name-service,0.1.21)
"@timestamp":"2026-09-16T09:10:25.868Z" "message":"Managed to upload one or more archives for submissionId b4e8d451-7632-4f2c-a412-9dca56486f27
"@timestamp":"2026-09-16T09:10:25.872Z" "message":"Request com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages by grpc:/127.0.0.1:56298: received a message GetPreferredPackagesRequest(\n  Packa
"@timestamp":"2026-09-16T09:10:25.872Z" "message":"Request com.digitalasset.canton.admin.participant.v30.PackageService/UploadDar by grpc:/127.0.0.1:53730: sending response UploadDarResponse(<HASH>
"@timestamp":"2026-09-16T09:10:25.875Z" "message":"Request com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages by grpc:/127.0.0.1:56298: failed with INTERNAL/Missing package-id <HASH>
```

The grep on `Storing package 45099e95` and `UploadDarResponse(45099e95` is what ties the first and fifth
lines to the same DAR (the redaction hides the id; the 12-char prefix is visible in section 8's listing).
"Managed to upload" is logged right after `packageStore.append` and right before
`packageMetadataView.updateMany` (section 7), so at 25.868 the view update of the dso-governance-0.1.26 DAR
begins; the query's auth claims are logged at 25.870 (section 4), its message is received at 25.872, and it
fails at 25.875; the upload's response (sent once `updateMany` has completed) goes out at 25.872. The
query's snapshot of the view was taken inside that update.

## 7. Mechanism, verified against the runtime jar 3.6.0-snapshot.20260910.20260.0.v90621933

Fetch and disassemble the exact binary (recipe: nix/canton-sources.json version -> canton.io tarball):

```
V=3.6.0-snapshot.20260910.20260.0.v90621933
curl -sSLo canton.tgz "https://www.canton.io/releases/canton-open-source-$V.tar.gz"; sha256sum canton.tgz | cut -c1-64
tar -xzf canton.tgz --wildcards "*/lib/canton-open-source-*.jar"
python3 -c "import zipfile,sys;z=zipfile.ZipFile(sys.argv[1]);print([n for n in z.namelist() if n.endswith('.class') and b'in package metadata view' in z.read(n)])" \
  canton-open-source-$V/lib/canton-open-source-$V.jar
```
```
286a459726110151f4b870439bcf108d92cf367b264b2af3b7c3bfdc47c2071a
['com/digitalasset/canton/store/packagemeta/PackageMetadata.class']
```

(a) `PackagePreferenceBackend.getPreferredPackages` takes ONE snapshot of the package metadata view,
collects all package ids for the requested package names, then walks dependencies from that same snapshot:

```
unzip -oq canton-open-source-$V/lib/canton-open-source-$V.jar 'com/digitalasset/canton/platform/PackagePreferenceBackend*.class' \
  'com/digitalasset/canton/participant/store/memory/MutablePackageMetadataViewImpl*.class' 'com/digitalasset/canton/participant/admin/PackageUploader*.class'
javap -p -c -l com/digitalasset/canton/platform/PackagePreferenceBackend.class | awk '/anonfun\$getPreferredPackages\$4\(/,/^$/' \
  | grep -E 'invoke(virtual|interface|special) .*(PackageVettingRequirements|allDependencySetsRecursively|findValidCandidate)|line 1(1[4-6]|2[12]):' | sed -E 's/^ +//' | cut -c1-150
```
```
4: invokevirtual #704                // Method com/digitalasset/canton/ledger/api/validation/GetPreferredPackagesRequestValidator$PackageVettingRequirements.value:()Lscala/collection/immutable/Map;
13: invokevirtual #509                // Method com/digitalasset/canton/ledger/api/validation/GetPreferredPackagesRequestValidator$PackageVettingRequirements.allPackageNames:()Lscala/collection/immutable/Set;
35: invokevirtual #719                // Method com/digitalasset/canton/store/packagemeta/PackageMetadata.allDependencySetsRecursively:(Lscala/collection/immutable/Set;)Lscala/collection/immutable/Map;
111: invokespecial #754                // Method findValidCandidate:(Lscala/collection/immutable/Map;Lcom/digitalasset/canton/tracing/TraceContext;)Lscala/util/Either;
line 114: 0
line 115: 12
line 116: 16
line 121: 32
line 122: 33
```

(b) `PackageMetadata.tryGet` throws when a walked dependency is absent (vendored 3.5.7-SNAPSHOT source; the
runtime LineNumberTable in the stack trace above, 51/61/92, matches these lines exactly):

```
cd <splice>; sed -n '89,93p' canton/community/base/src/main/scala/com/digitalasset/canton/store/packagemeta/PackageMetadata.scala
sed -n '64,66p' canton/community/base/src/main/scala/com/digitalasset/canton/store/packagemeta/PackageMetadata.scala
```
```
  private def tryGet(packageId: Ref.PackageId): Ast.PackageSignature =
    packages.getOrElse(
      packageId,
      throw new IllegalStateException(s"Missing package-id $packageId in package metadata view"),
    )
  /** Compute the set of dependencies recursively. Assuming that the package store is closed under
    * dependencies, it throws an exception if a package is unknown.
```

(c) The view is NOT updated atomically per DAR. `MutablePackageMetadataViewImpl.updateMany` does a
`Seq.foreach` and one `AtomicReference.updateAndGet(_ |+| other)` per package (runtime bytecode):

```
javap -p -c com/digitalasset/canton/participant/store/memory/MutablePackageMetadataViewImpl.class \
  | awk '/anonfun\$updateMany\$2\(/,/^$/' | grep -E 'invoke' | sed -E 's/^ +//' | cut -c1-120
javap -p -c com/digitalasset/canton/participant/store/memory/MutablePackageMetadataViewImpl.class \
  | awk '/anonfun\$updateMany\$4\(/,/^$/' | grep -E 'invoke.*(Semigroup|bar\$plus\$bar)' | sed -E 's/^ +//' | cut -c1-120
```
```
3: invokedynamic #916,  0            // InvokeDynamic #14:apply:(Lcom/digitalasset/canton/participant/store/memory/MutablePackag
8: invokeinterface #922,  2          // InterfaceMethod scala/collection/immutable/Seq.foreach:(Lscala/Function1;)V
40: invokevirtual #831                // Method com/digitalasset/canton/store/packagemeta/PackageMetadata$Implicits$.packageMetadataSemigroup:()Lcats/kernel/Semigroup;
43: invokevirtual #835                // Method cats/implicits$.catsSyntaxSemigroup:(Ljava/lang/Object;Lcats/kernel/Semigroup;)Lcats/syntax/SemigroupOps;
47: invokevirtual #840                // Method cats/syntax/SemigroupOps.$bar$plus$bar:(Ljava/lang/Object;)Ljava/lang/Object;
```

Same shape in the vendored source (3.5.7-SNAPSHOT,
`canton/community/participant/src/main/scala/com/digitalasset/canton/participant/store/memory/PackageMetadataView.scala:91-107`):

```
sed -n '91,101p' canton/community/participant/src/main/scala/com/digitalasset/canton/participant/store/memory/PackageMetadataView.scala
```
```
  def updateMany(newPackagesMetadata: Seq[PackageMetadata])(implicit
      tc: TraceContext
  ): FutureUnlessShutdown[Unit] =
    mutatePackageMetadataExecutionQueue.execute(
      execution = Future {
        newPackagesMetadata.foreach(other =>
          packageMetadataRef.updateAndGet {
            case Some(packageMetadata) => Some(packageMetadata |+| other)
```

(d) The main package is merged FIRST: `PackageUploader.uploadDar` builds `allPackages = mainPackage +: dependencies`
(runtime: `List.$plus$colon` in `uploadDar`; vendored 3.5.7-SNAPSHOT `PackageUploader.scala:124-137`), and
`persist` runs `packageStore.append` -> log "Managed to upload ..." -> `packageMetadataView.updateMany(darPackageMetadata)`:

```
javap -p -c com/digitalasset/canton/participant/admin/PackageUploader.class | awk '/ uploadDar\(/,/^$/' | grep -E 'plus\$colon' | sed -E 's/^ +//' | cut -c1-110
cd <splice>; sed -n '124,137p' canton/community/participant/src/main/scala/com/digitalasset/canton/participant/admin/PackageUploader.scala
```
```
6: invokevirtual #561                // Method scala/collection/immutable/List.$plus$colon:(Ljava/lang/Object;)Ljava/lang/Object;
    val allPackages = mainPackage +: dependencies
    def persist(
        dar: Dar,
        packages: List[(PackageInfo, DamlLf.Archive)],
        uploadedAt: CantonTimestamp,
    ): FutureUnlessShutdown[Unit] =
      for {
        _ <- packageStore.append(packages, uploadedAt, dar)
        _ = logger.debug(
          s"Managed to upload one or more archives for submissionId $submissionId"
        )
        darPackageMetadata = allPackages.map { case (_, (pkgId, pkg)) =>
          PackageMetadata.from(pkgId, pkg)
        }
        _ <- packageMetadataView.updateMany(darPackageMetadata)
```

So between the first `updateAndGet` (dso-governance 0.1.26 itself) and the one for ANS 0.1.21 (its 4th
element, after the ghc-stdlib and splice-amulet entries in the store order above), a reader that snapshots
the view sees a dso-governance package whose dependency is not there. That is the exception.

## 8. Who was uploading, and why then: the SV app's own vetting flow after the test's config-change vote

The test changes AmuletConfig to the latest package versions via a vote at 09:10:08-10; each SV app then
uploads the DARs it needs (span `upload_dars`, `apps/common/src/main/scala/org/lfdecentralizedtrust/splice/util/PackageVetting.scala:265`):

```
zcat canton_network_test.clog.gz | grep -aE '(Running|Finished) clue: (Change AmuletConfig to latest packages|\(act\) sv1-3 accept vote request for upgraded packages)' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,90}' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -a .
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:10:(1[0-9]|2[0-9])' \
  | grep -a 'ApiClientRequestLogger:BootstrapPackageConfigIntegrationTest/config=c5943284/SV=sv1' | grep -a 'PackageService/UploadDar' \
  | grep -aoE '"span-name":"[^"]*"' | sort | uniq -c
cd <splice>; grep -n 'withSpan("upload_dars")' apps/common/src/main/scala/org/lfdecentralizedtrust/splice/util/PackageVetting.scala
```
```
"@timestamp":"2026-09-16T09:10:08.562Z" "message":"Running clue: Change AmuletConfig to latest packages
"@timestamp":"2026-09-16T09:10:09.383Z" "message":"Running clue: (act) sv1-3 accept vote request for upgraded packages
"@timestamp":"2026-09-16T09:10:10.810Z" "message":"Finished clue: (act) sv1-3 accept vote request for upgraded packages
"@timestamp":"2026-09-16T09:10:38.119Z" "message":"Finished clue: Change AmuletConfig to latest packages
     72 "span-name":"upload_dars"
265:      _ <- withSpan("upload_dars") { implicit tc => _ =>
```

sv1's admin API received 24 DAR uploads on one connection between 09:10:11.9 and 09:10:27.3, several of
them overlapping (in the excerpt below five requests arrive within 30 ms at 24.047-24.076 and are answered
between 25.365 and 26.683), while the trigger polls GetPreferredPackages every ~1 s throughout:

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:10:(1[0-9]|2[0-9])' \
  | grep -a 'ApiRequestLogger:participant=sv1Participant' | grep -a 'UploadDar by' | grep -ac 'received a message'
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:10:2[4-6]' \
  | grep -a 'ApiRequestLogger:participant=sv1Participant' | grep -a 'UploadDar by' | grep -aE 'received a message|sending response' \
  | grep -aoE '"@timestamp":"[^"]*"|(received a message UploadDarRequest\(UploadDarData\(ByteString, [a-z0-9.-]+|sending response UploadDarResponse\([0-9a-f]{12})' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -a . | cut -c1-140
```
```
24
"@timestamp":"2026-09-16T09:10:24.011Z" sending response UploadDarResponse(a78daeeb1a4a
"@timestamp":"2026-09-16T09:10:24.047Z" received a message UploadDarRequest(UploadDarData(ByteString, splice-wallet-payments-0.1.21
"@timestamp":"2026-09-16T09:10:24.051Z" received a message UploadDarRequest(UploadDarData(ByteString, splice-wallet-payments-0.1.20
"@timestamp":"2026-09-16T09:10:24.065Z" received a message UploadDarRequest(UploadDarData(ByteString, splice-amulet-name-service-0.1.23
"@timestamp":"2026-09-16T09:10:24.073Z" received a message UploadDarRequest(UploadDarData(ByteString, splice-dso-governance-0.1.28
"@timestamp":"2026-09-16T09:10:24.076Z" received a message UploadDarRequest(UploadDarData(ByteString, splice-dso-governance-0.1.26
"@timestamp":"2026-09-16T09:10:25.365Z" sending response UploadDarResponse(340afccb7021
"@timestamp":"2026-09-16T09:10:25.872Z" sending response UploadDarResponse(45099e955ce4
"@timestamp":"2026-09-16T09:10:26.182Z" sending response UploadDarResponse(dc1a2f4ff477
"@timestamp":"2026-09-16T09:10:26.255Z" sending response UploadDarResponse(9cffe65feb66
"@timestamp":"2026-09-16T09:10:26.683Z" sending response UploadDarResponse(eeb461e9e430
"@timestamp":"2026-09-16T09:10:26.688Z" received a message UploadDarRequest(UploadDarData(ByteString, splice-amulet-name-service-0.1.21
"@timestamp":"2026-09-16T09:10:26.692Z" received a message UploadDarRequest(UploadDarData(ByteString, splice-amulet-name-service-0.1.22
```

The test itself documents that this phase is a race it only mitigates by time
(`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BootstrapPackageConfigIntegrationTest.scala:212-214`):

```
sed -n '212,214p' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BootstrapPackageConfigIntegrationTest.scala
```
```
    // 20s picked empirically to be far enough in the future that the voting can go through before that date.
    // it must also leave enough time for the dars to be uploaded and vetting to happen to prevent command failures
    val expiration = new RelTime(19_000_000)
```

## 9. Recovery: the trigger's next poll 1.3 s later succeeded; 170 of 171 calls in that span succeeded

```
zcat canton_network_test.clog.gz | grep -a 'span-name":"CreateBootstrapExternalPartyConfigStateInstructionTrigger-work' | grep -a 'SV=sv1' \
  | grep -a 'GetPreferredPackages to 127.0.0.1:5101' | grep -aE 'failed with|succeeded' | grep -aE '"@timestamp":"2026-09-16T09:10:2[3-9]' \
  | grep -aoE '"@timestamp":"[^"]*"|failed with [A-Z]+|succeeded' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -a .
zcat canton_network_test.clog.gz | grep -a 'span-name":"CreateBootstrapExternalPartyConfigStateInstructionTrigger-work' | grep -a 'SV=sv1' \
  | grep -a 'GetPreferredPackages to 127.0.0.1:5101' | grep -aoE 'failed with [A-Z]+|succeeded' | sort | uniq -c
```
```
"@timestamp":"2026-09-16T09:10:23.909Z" succeeded
"@timestamp":"2026-09-16T09:10:24.883Z" succeeded
"@timestamp":"2026-09-16T09:10:25.971Z" failed with INTERNAL
"@timestamp":"2026-09-16T09:10:27.288Z" succeeded
"@timestamp":"2026-09-16T09:10:28.205Z" succeeded
"@timestamp":"2026-09-16T09:10:29.311Z" succeeded
      1 failed with INTERNAL
    170 succeeded
```

No other WARN/ERROR in the harness log between 09:10:20 and 09:11:29 besides the three flagged lines.

## 10. Splice caller: INTERNAL is non-retryable by design and logged at ERROR by the client logger

Call chain (files identical between HEAD and f1ee318e39, `git diff --stat f1ee318e39 HEAD -- <files>` is empty):
`CreateBootstrapExternalPartyConfigStateInstructionTrigger.retrieveTasks` ->
`PackageVersionSupport.supports24hSubmissionDelayDsoGovernance(parties, context.clock.now)` ->
`isPackageSupported(..., at, ...)` -> `SpliceLedgerConnection.getSupportedPackageVersion(synchronizerId, packageRequirements, at)`
(the retry wrapper that logs "Get the supported package version for packageRequirements ... with vetting time <at>") ->
`LedgerClient.getSupportedPackageVersion` -> `stub.getPreferredPackages(GetPreferredPackagesRequest(...))`.

```
cd <splice>
grep -n 'supports24hSubmissionDelayDsoGovernance\|context.clock.now' apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/confirmation/CreateBootstrapExternalPartyConfigStateInstructionTrigger.scala | cut -c1-120
sed -n '533,543p' apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala
sed -n '669,672p' apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala
sed -n '246,247p' apps/common/src/main/scala/org/lfdecentralizedtrust/splice/admin/api/client/ApiClientRequestLogger.scala
sed -n '105,111p' apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/PollingTrigger.scala
```
```
41:      supports24hSubmissionDelay <- packageVersionSupport
42:        .supports24hSubmissionDelayDsoGovernance(
44:          context.clock.now,
    private val retryableStatusCodes = Seq(
      // Canton registers its services after starting the top-level gRPC server, which means we get UNIMPLEMENTED errors
      // for a short period of during the startup of Canton.
      Status.Code.UNIMPLEMENTED,
      Status.Code.UNAVAILABLE,
      Status.Code.NOT_FOUND,
      Status.Code.ALREADY_EXISTS,
      Status.Code.FAILED_PRECONDITION,
      Status.Code.DEADLINE_EXCEEDED,
      Status.Code.ABORTED,
      Status.Code.UNAUTHENTICATED,
                        // DbStorage instance could still be in passive state during startup
                        (statusCode == Status.Code.INTERNAL && description.contains(
                          "DbStorage instance is not active"
                        )) ||
      } else if (enhancedStatus.getCode == Status.Code.INTERNAL) {
        logger.error(message, enhancedStatus.getCause)
  private val retryable = RetryProvider.RetryableError(
    "pollingTriggerTask",
    Seq.empty,
    Map.empty,
    "transient",
    "non-transient",
    s"restarting after $pollingInterval",
```

INTERNAL is retryable only with the "DbStorage instance is not active" description; the ledger API redacts
INTERNAL to "An error occurred. Please contact the operator ...", so no description-based rule can ever match
this case. The trigger's PollingTrigger restarts after its polling interval regardless (WARN line 3), which
is why there was no functional impact.

## 11. Ignore patterns: the server side is already allowlisted (and matched here); the client side is not

```
cd <splice>
grep -rn 'Missing package-id' project/ignore-patterns/ | cut -c1-120
git log --format='%h %ad %an %s' --date=short -S 'Missing package-id' -- project/ignore-patterns
git show 8dbadf1d3a -- project/ignore-patterns | grep -E '^\+' | grep -v '^+++'
grep -cE 'INTERNAL|GetPreferredPackages|An error occurred|pollingTriggerTask|non-transient|non-retryable' project/ignore-patterns/canton_network_test_log.ignore.txt
grep -n 'canton_network_test' build.sbt | head -1
```
```
project/ignore-patterns/canton_log.ignore.txt:210:INTERNAL/Missing package-id.*in package metadata view
8dbadf1d3a 2026-07-09 Ignore spurious Missing package-id error (#6356)
+
+# TODO(DACH-NY/cn-test-failures#9136) remove once Canton fixes this race condition from not storing packages in dependency order
+INTERNAL/Missing package-id.*in package metadata view
0
2328:  checkLogs("log/canton_network_test.clog", Seq("canton_network_test_log"))
```

In this run the server-side ERROR was indeed swallowed by that pattern (it is listed under the IGNORED
entries for canton_before_shutdown.clog in the job console), so only the three client lines remain:

```
grep -anE 'Found ignored entries in log/canton_before_shutdown|Missing package-id' job-104730519175.log | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | cut -c1-200
```
```
11610:2026-09-16T09:11:33.5689352Z Found ignored entries in log/canton_before_shutdown.clog:
11807:2026-09-16T09:11:33.6408458Z ***"@timestamp":"2026-09-16T09:10:25.875Z","message":"Request com.daml.ledger.api.v2.interactive.InteractiveSubmissionService/GetPreferredPackages by grpc:/127.0.0.1:56298: failed with INTERNAL/M
```

#6356 diagnosed the race as "not storing packages in dependency order"; section 7 sharpens that: the store
order is irrelevant, the view is merged one package at a time with the main package first, and any reader
that snapshots the view between those merges and walks dependencies throws. The client-side gap: the
allowlist keyed on "Missing package-id" cannot see the redacted client message, and a GetPreferredPackages
caller inside a splice app (as opposed to the test harness) had not hit it before.

## 12. Not a regression

```
cd <splice>
git log --format='%h %ad %s' --date=short -S 'CreateBootstrapExternalPartyConfigStateInstructionTrigger' -- apps | tail -1
git log --oneline -3 f1ee318e39 -- nix/canton-sources.json | head -1
git log --oneline d6e5120026..f1ee318e39 | wc -l
```
```
0e64013756 2026-03-16 Support transfers with 24h validity period (#3487)
d6e5120026 Upgrade Canton to 3.6.0-snapshot.20260910.20260.0.v90621933 (#7264)
31
```

The trigger has polled GetPreferredPackages since 2026-03-16; the Canton snapshot has been on main for 31
commits; the per-package `updateMany` exists in the vendored 3.5.7 source and in the 3.6.0 runtime alike.
The failure needs a GetPreferredPackages call to land inside a ~1-4 ms merge window of a DAR upload whose
main package depends on a not-yet-merged package; BootstrapPackageConfigIntegrationTest produces 24 such
uploads per SV while 3 to 5 triggers poll every second, so it is the shard most exposed.

## Root cause / hypothesis

Proven (from logs and the runtime jar):
- The three flagged lines are one gRPC call: sv1 SV app, `CreateBootstrapExternalPartyConfigStateInstructionTrigger`,
  `GetPreferredPackages` for splice-dso-governance at vetting time 09:10:25.840593Z, tid ce37db34...,
  to sv1Participant's ledger API (5101).
- Server side, the same tid fails at 09:10:25.875 with `IllegalStateException("Missing package-id
  b342bbbd... in package metadata view")` thrown from `PackageMetadata.tryGet` via
  `allDependencySetsRecursively`, called from `PackagePreferenceBackend.getPreferredPackages`
  (PackagePreferenceBackend.scala:122 in 3.6.0-snapshot.20260910.20260.0.v90621933).
- b342bbbd... is splice-amulet-name-service 0.1.21, first stored on sv1 at 09:10:25.818 as a dependency of the
  splice-dso-governance-0.1.26 DAR (main 45099e95..., stored 25.817). Among sv1's dso-governance versions only
  0.1.26 depends on it, so 0.1.26 was in the snapshot the query used while its dependency was not.
- That DAR's `packageStore.append` finished at 25.868 ("Managed to upload"), which is the point where
  `updateMany` starts; the query's auth claims were logged at 25.870 and its message received at 25.872,
  the upload response left at 25.872, the query failed at 25.875.
- `MutablePackageMetadataViewImpl.updateMany` merges packages with one `AtomicReference.updateAndGet` per
  package via `Seq.foreach`, and `PackageUploader.uploadDar` orders the list `mainPackage +: dependencies`
  (both confirmed by javap on the runtime jar, sha256 286a4597...).
- The trigger restarted after 1 s; its next call at 09:10:27.288 succeeded; 1 of 745 GetPreferredPackages
  calls in the run failed; both tests passed.
- The server-side ERROR matched the existing allowlist entry from #6356 (canton_log.ignore.txt:210); the
  client-side lines carry the redacted INTERNAL text and match nothing in canton_network_test_log.ignore.txt.

Inferred:
- Sub-millisecond ordering between the first `updateAndGet` (main package) and the query's `getSnapshot`
  is not logged; the 25.868 -> 25.870/25.872 -> 25.875 sequence and the fact that exactly the main package
  was present and exactly a dependency was absent leave no other candidate.

## Duplicates / related

- Same Canton bug, server side: splice #6356 "Ignore spurious Missing package-id error" (2026-07-09), tracking DACH-NY/cn-test-failures#9136 (repo not readable from this sandbox; status unknown).
  That fix only covers `canton_log`, not the app-side mirror of the error.
- Sibling test-side comment: BootstrapPackageConfigIntegrationTest.scala:212-214 already treats "DARs
  uploaded and vetted before commands run" as a timing race it mitigates with a 20 s buffer.
- No earlier ci-triage packet mentions GetPreferredPackages, PackagePreferenceBackend or
  CreateBootstrapExternalPartyConfigStateInstructionTrigger (`grep -rl` over ci-triage/ is empty).
- Same failure mode (all tests pass, checkErrors flags handled lines): 10140 (SERVER_OVERLOADED),
  10094 (ack stall), 10084 (indexer reconnect WARN).

## Suggested next step / owner

Upstream (Canton, participant / ledger API): the real fix is one of
1. make `MutablePackageMetadataViewImpl.updateMany` atomic per call (fold the DAR's `PackageMetadata`s with
   `|+|` first, then a single `updateAndGet`), so a snapshot is always closed under dependencies for
   uploaded DARs; or
2. make `PackagePreferenceBackend.getPreferredPackages` tolerate a missing dependency (treat the candidate
   as not-yet-known instead of throwing an `IllegalStateException` that becomes a redacted INTERNAL).
Cite against 3.6.0-snapshot.20260910.20260.0.v90621933: PackagePreferenceBackend.scala:114-127,
PackageMetadata.scala:51/61/92, MutablePackageMetadataViewImpl (PackageMetadataView.scala:95-107 per the
runtime LineNumberTable), PackageUploader.uploadDar. Link cn-test-failures#9136 and re-check whether it is
still open; this run shows the race is unchanged in 3.6.

Splice interim (CI / test infra), pick one:
- Allowlist the client mirror of the already-allowlisted server error in
  `project/ignore-patterns/canton_network_test_log.ignore.txt`, scoped to the call and the redacted text so a
  real INTERNAL elsewhere still fails the build, with the same TODO(cn-test-failures#9136) marker:
  `InteractiveSubmissionService/GetPreferredPackages to 127.0.0.1:[0-9]+: failed with INTERNAL/An error occurred`
  `Get the supported package version for packageRequirements .* failed with a non-retryable error`
  `pollingTriggerTask' failed with a non-transient error, restarting after .*statusCode=INTERNAL\\ndescription=An error occurred`
  (the third is the broadest; it is bounded by the redacted-INTERNAL description.)
- Or splice-side retry: give `SpliceLedgerConnection.getSupportedPackageVersion` an `additionalConditions`
  entry making INTERNAL retryable for this one operation (RetryProvider.scala:533-676). That turns lines 2
  and 3 into INFO "transient" logs (RetryProvider.scala:680-697 log transient retries at INFO), but line 1
  (ApiClientRequestLogger.scala:246-247 logs every INTERNAL at ERROR) still needs the first allowlist entry.

Owner: Canton participant team for the view atomicity (via cn-test-failures#9136); splice CI for the
interim allowlist.

## Summary

resource-intensive(1), job 104730519175, ref 10144 (confirmed by Raymond 2026-09-17), canton
3.6.0-snapshot.20260910.20260.0.v90621933. Both tests passed; checkErrors flags 3 lines in
canton_network_test.clog, all one `GetPreferredPackages` call from sv1's
CreateBootstrapExternalPartyConfigStateInstructionTrigger that got a redacted INTERNAL. Server side the call
threw "Missing package-id <splice-amulet-name-service 0.1.21> in package metadata view" because it snapshotted
sv1Participant's package metadata view while the SV app's post-vote DAR upload of splice-dso-governance-0.1.26
was being merged into it one package at a time, main package first (verified by javap on the runtime jar).
Known Canton race (splice #6356 allowlisted the server-side line in July, cn-test-failures#9136); the
app-side mirror is not allowlisted because the ledger API redacts INTERNAL. Trigger retried after 1 s and
succeeded. Not a regression.
