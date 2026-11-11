# 11353 - SvFrontendIntegrationTest Grant/Update/Amend/Revoke Featured App Right fails on mainnet Daml: appKind needs splice-amulet 0.1.25 (run 42963305445)

New, family Y (test needs newer Daml packages than its scalatest tag). The scheduled "Integration tests against
mainnet Daml version" run on main 9dd892fe2e (canton-network/splice#8062) ran with splice-amulet 0.1.24 and
splice-dso-governance 0.1.30 (MainNet's versions). #8062 made the test case "Grant, Update, Amend and Revoke Featured
App Right" fill the new `appKind` and `opsParties` fields of the GrantFeaturedAppRight form (added to splice-amulet in
#7627, version 0.1.25). The SV app's createVoteRequest submission is rejected by Canton with
`COMMAND_PREPROCESSING_FAILED ... An optional contract field ("appKind") with a value of Some may not be dropped during
downgrading`, the SV UI returns 400, sv1 is not redirected, and the check fails after 20 s. The test case has no
version tag, so the nightly's `-l ...SpliceAmulet_0_1_25` exclusion does not skip it. The same shard passed on main's
post-merge CI at the same sha (latest packages). Deterministic: every mainnet-Daml nightly fails until the test is
tagged. 32 tests succeeded, 1 failed.

- Run: https://github.com/canton-network/splice/actions/runs/42963305445, main 9dd892fe2e ("SV UI: Add support for new fields in GrantFeaturedAppRight vote creation (#8062)"), job 130334974223 `build / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`, attempt 1.
- Runtime canton: not looked up (irrelevant: the rejection is a package-version downgrade, see 3).
- Component: test (missing version tag).

## 1. Failing assertion

```
C=log/inbox-1111/130334974223.clean.log   # job log with ANSI escapes stripped
grep -anE 'Featured App Right|Tests: succeeded' $C | cut -c1-300; sed -n '13641p' $C
```
```
13640:2026-11-11T05:25:26.7470062Z [info] - should Grant, Update, Amend and Revoke Featured App Right *** FAILED ***
13775:2026-11-11T05:43:53.2918911Z [info] Tests: succeeded 32, failed 1, canceled 0, ignored 0, pending 0
13779:2026-11-11T05:43:53.2921365Z [info] - Grant, Update, Amend and Revoke Featured App Right *** FAILED ***
2026-11-11T05:25:26.7477208Z [info]   Check sv1 is redirected to the governance page after successful submission for sv1 operator can create a new proposal None was empty 'Initiate Proposal' button (SvFrontendIntegrationTest.scala:420)
```

## 2. Package versions of the run and excluded tags

The nightly pins MainNet's versions and excludes tags of newer versions:

```
grep -aoE "INITIAL_PACKAGE_VERSIONS=.\*\*\*[^']*" $C | head -1 | tr ',' '\n' | grep -E 'splice-amulet"|splice-dso-governance'
sed -n '10677p' $C | tr ' ' '\n' | grep scalatesttags | sed 's/.*scalatesttags\.//' | grep -E 'Amulet_|DsoGovernance'
```
```
INITIAL_PACKAGE_VERSIONS='***"splice-amulet": "0.1.24"
 "splice-dso-governance": "0.1.30"
SpliceAmulet_0_1_25
SpliceDsoGovernance_0_1_31
```

## 3. The rejected submission (artifact logs-frontend-wall-clock-time-2)

```
A=log/11353/logs-frontend-wall-clock-time-2
zcat $A/canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-11-11T05:2[45]' | grep -aE 'Creating proposal: SRARC_GrantFeaturedAppRight|\(check\) sv1 is redirected|voterequest/create resulted|voterequest/create from \(127.0.0.1:39084\): Responding|Test failed: .SvFrontendIntegrationTest/SV UIs should Grant' | sed -E 's/","logger_name.*//; s/^\{"@timestamp":"//; s/","message":"/ /' | cut -c1-330
```
```
2026-11-11T05:24:21.751Z Running clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:24:23.945Z Finished clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:24:43.344Z Running clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:24:45.477Z Finished clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:24:56.022Z Running clue: Creating proposal: SRARC_GrantFeaturedAppRight
2026-11-11T05:25:05.946Z Running clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:25:05.984Z Request to http://localhost:5114/api/sv/v0/admin/sv/voterequest/create resulted in a gRPC StatusRuntimeException: INVALID_ARGUMENT: COMMAND_PREPROCESSING_FAILED(8,495aa838): An optional contract field (\"appKind\") with a value of Some may not be dropped during downgrading.
2026-11-11T05:25:05.985Z HTTP POST /api/sv/v0/admin/sv/voterequest/create from (127.0.0.1:39084): Responding with status code: 400 Bad Request, took 124 ms
2026-11-11T05:25:25.954Z Failed clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:25:26.149Z Failed clue: Creating proposal: SRARC_GrantFeaturedAppRight
2026-11-11T05:25:26.187Z Test failed: 'SvFrontendIntegrationTest/SV UIs should Grant, Update, Amend and Revoke Featured App Right', message: Check sv1 is redirected to the governance page after successful submission for sv1 operator can create a new proposal None was empty 'Initiate Proposal' button, location: SeeStackDepthExcep
2026-11-11T05:25:32.557Z Running clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:25:34.676Z Finished clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:25:52.161Z Running clue: (check) sv1 is redirected to the governance page after successful submission
2026-11-11T05:25:54.461Z Finished clue: (check) sv1 is redirected to the governance page after successful submission
```

The earlier proposals in the suite (OffboardSv, 05:24) and the later ones (05:25:32 on) succeed; only the Grant
submission is rejected. Its request body carries the new fields:

```
zcat $A/canton_network_test.clog.gz | grep -a 'voterequest/create from (127.0.0.1:39084): Received entity data' | grep -a '05:25:05' | sed -E 's/","logger_name.*//; s/^\{"@timestamp":"//; s/","message":"/ /; s/::1220[0-9a-f]{64}/::1220.../g' | cut -c1-900
```
```
2026-11-11T05:25:05.860Z HTTP POST /api/sv/v0/admin/sv/voterequest/create from (127.0.0.1:39084): Received entity data: {\"requester\":\"digital-asset-2-4a1873d6::1220...\",\"action\":{\"tag\":\"ARC_DsoRules\",\"value\":{\"dsoAction\":{\"tag\":\"SRARC_GrantFeaturedAppRight\",\"value\":{\"provider\":\"digital-asset-eng-3-4a1873d6::1220...\",\"activityWeight\":\"2.5\",\"appKind\":\"FAK_AssetMoving\",\"opsParties\":[\"digital-asset-eng-2-4a1873d6::1220...\"]}}}},\"url\":\"https://new-proposal-url.com/\",\"description\":\"This is a summary of the proposal\",\"expiration\":{\"microseconds\":\"604794142000\"}}
```

## 4. #8062 added the new fields to the test; the fields come with splice-amulet 0.1.25

```
git show 9dd892f -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvFrontendIntegrationTest.scala | grep -E '^\+.*(appKind|opsParties|FAK_)'
```
```
+      // opsParties must not include the provider; use sv2's party (valid and distinct).
+      // First, create a Grant proposal for the provider, exercising the new appKind and
+      // opsParties fields (CIP-104 1.1).
+        selectMuiOptionByValue("grant-featured-app-appKind-dropdown", "FAK_AssetMoving")
+        // does not obscure the opsParties controls below it.
+        // opsParties is an add-a-row widget: add one row, then fill it.
+        click on testId("grant-featured-app-opsParties-add")
+          inside(find(testId("grant-featured-app-opsParties-row-0"))) { case Some(row) =>
+          featuredAppRight.value.payload.appKind.toScala shouldBe Some(
+            FeaturedAppKind.FAK_ASSETMOVING
+          featuredAppRight.value.payload.opsParties.toScala
```

```
git log --format='%h %ad %s' --date=short -S'appKind : Optional FeaturedAppKind' 9dd892f -- daml/splice-amulet/daml/Splice/Amulet.daml | tail -1
git show d0a311567d:daml/splice-amulet/daml.yaml | grep -E '^version'
```
```
d0a311567d 2026-10-28 Daml for TBAR v1.1 Amendment (#7627)
version: 0.1.25
```

## 5. The test case has no version tag; the tag that excludes 0.1.25 tests exists

```
git show 9dd892f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvFrontendIntegrationTest.scala | grep -nE 'Grant, Update, Amend|taggedAs|scalatesttags'
git show 9dd892f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TokenStandardTransferIntegrationTest.scala | sed -n '434,436p'
```
```
585:    "Grant, Update, Amend and Revoke Featured App Right" in { implicit env =>
    "buy traffic through a transfer to the traffic purchase receiver" taggedAs Tag(
      "org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25"
    ) in { implicit env =>
```

## 6. The Amend step (#8061) also needs newer packages (not reached in this run)

The test's Amend step creates `SRARC_CreateFeaturedAppConfigUpdateInstruction`, a DsoRules action added in #7627,
when splice-dso-governance was already 0.1.31; MainNet runs 0.1.30 (section 2). #8061 added the step on 2026-11-10,
after the 11-10 nightly's sha, so no mainnet-Daml run has reached it yet. Likely to fail the same way once the Grant
step is fixed without a tag (not verified at runtime).

```
git log --format='%h %cI %s' -S'SRARC_CreateFeaturedAppConfigUpdateInstruction' -- daml/splice-dso-governance/daml | tail -1
git show d0a311567d~1:daml/splice-dso-governance/daml.yaml | grep '^version'
git log -1 --format='%h %cI %s' e59889cad0
git merge-base --is-ancestor e59889cad0 885cd84668 && echo "8061 in 11-10 nightly" || echo "8061 NOT in 11-10 nightly"
```
```
d0a311567d 2026-10-28T07:13:01+01:00 Daml for TBAR v1.1 Amendment (#7627)
version: 0.1.31
e59889cad0 2026-11-10T09:25:18Z SV UI: Add support for new vote creation to update featured app config (#8061)
8061 NOT in 11-10 nightly
```

## 7. Same shard passed on main post-merge CI at the same sha; mainnet-Daml workflow history

```
gh api "repos/canton-network/splice/actions/runs/42948633392" --jq '"\(.name) \(.head_sha[0:10]) attempt=\(.run_attempt) \(.conclusion)"'
gh api "repos/canton-network/splice/actions/runs/42948633392/attempts/1/jobs?per_page=100" --jq '.jobs[]|select(.name|test("frontend-wall-clock-time \\(2\\)"))|"\(.id) \(.name) \(.conclusion)"'
grep -aE 'Grant, Update, Amend and Revoke|Tests: succeeded' log/11353/main-130287695908.log | cut -c1-260
```
```
CI post-merge to main or release-line branches 9dd892fe2e attempt=2 success
130287695908 ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2) success
2026-11-11T03:11:53.2095115Z [info] *** Test still running after 1 minute: suite name: SvFrontendIntegrationTest, test name: SV UIs should Grant, Update, Amend and Revoke Featured App Right. 
2026-11-11T03:12:22.4111062Z [info] *** Test still running after 1 minute, 30 seconds: suite name: SvFrontendIntegrationTest, test name: SV UIs should Grant, Update, Amend and Revoke Featured App Right. 
2026-11-11T03:29:56.5489015Z [info] Tests: succeeded 35, failed 0, canceled 0, ignored 0, pending 0
```

```
gh api "repos/canton-network/splice/actions/workflows/167847128/runs?per_page=8" --jq '.workflow_runs[]|"\(.id) \(.created_at) \(.head_branch) \(.head_sha[0:10]) \(.event) \(.conclusion)"'
gh api "repos/canton-network/splice/actions/runs/42759795825/jobs?per_page=100&filter=latest" --jq '.jobs[]|select(.name|test("frontend-wall-clock-time \\(2\\)"))|"\(.id) \(.conclusion)"'
gh api "repos/canton-network/splice/actions/runs/42963305445/jobs?filter=latest&per_page=100" --jq '.jobs[]|select(.conclusion=="failure")|.name'
```
```
42963305445 2026-11-11T05:04:23Z main 9dd892fe2e schedule failure
42759795825 2026-11-10T05:04:28Z main 885cd84668 schedule failure
42565193320 2026-11-09T05:04:32Z main 0b2ccec235 schedule failure
42449239264 2026-11-08T05:03:23Z main 0b2ccec235 schedule failure
42341066277 2026-11-07T05:03:17Z main 0b2ccec235 schedule failure
42166190199 2026-11-06T05:04:17Z main 01436df75f schedule success
41971657081 2026-11-05T05:04:18Z main 2930ba1a4b schedule success
41776986892 2026-11-04T05:04:28Z main 9ab7e060e0 schedule failure
129642910955 success
build / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)
```

The 11-11 run is the first on a sha with #8062 (merged 2026-11-11T02:49:34Z); the same shard passed in the 11-10
nightly. This is the only failed job of the 11-11 nightly. The earlier red nights are other issues (11274, 11255,
11275; round 2026-11-09T084535Z-nightly).

## 8. Duplicate check

```
grep -c 'Featured App Right' ci-triage/known-families.md ci-triage/evidence/INDEX.tsv
for n in 11186 11340 11330 11310 11213; do echo "$n: $(gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"\(.state) \(.title)"')"; done
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "may not be dropped during downgrading"' --jq '.items[]|"\(.number) \(.state) \(.title)"'
```
```
ci-triage/known-families.md:0
ci-triage/evidence/INDEX.tsv:0
11186: open SvFrontendIntegrationTest: expiring proposal closed by sv2-sv4 before the in-flight check (trigger paused only on sv1)
11340: closed GHA Run 42823122392 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11330: closed GHA Run 42790498517 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11310: closed GHA Run 42646231899 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11213: closed GHA Run 41995289384 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
10078 closed UnsupportedPackageVettingIntegrationTest: ReceiveSvRewardCouponTrigger fails with INTERPRETATION_UPGRADE_ERROR_TRANSLATION_FAILED
10110 closed Log Flake: "The operation 'processTaskWithRetry' failed" and "Skipping processing of \nTask(\n  dsoRulesCid"
9965 closed UnsupportedPackageVettingIntegrationTest: Type checking fails during downgrading
7546 closed COMMAND_PREPROCESSING_FAILED: ("externalPartyConfigStateTickDuration") with a value of Some may not be dropped during downgrading
5292 closed Integration tests against mainnet Daml version failing because `An optional contract field ("initialRound") with a value of Some may not be dropped during downgrading.`
4734 closed COMMAND_PREPROCESSING_FAILED: An optional contract field "sv" with a value of Some may not be dropped during downgrading.
```

Per hit: 11186 is a different step (expiring proposal, AS). 11340, 11330, 11310, 11213 were filed before #8062 was
merged (2026-11-11), so they cannot be this failure. 10078, 10110, 9965: UnsupportedPackageVetting / other. 7546,
5292, 4734: the same class of error (new optional field set against older packages) on other fields, all closed long
ago; precedent, not duplicates. No open issue for this test and error: new.

## 9. Suggested assignee

#8062 author JYC11 and merger dfordivam are not assignable in cn-test-failures; the co-author and approver
meiersi-da is.

```
for u in JYC11 dfordivam meiersi-da; do printf "%s: " $u; gh api repos/DACH-NY/cn-test-failures/assignees/$u -i 2>&1 | head -1; done
git log -1 --format=%B 9dd892f | grep -i co-authored
gh api repos/canton-network/splice/pulls/8062/reviews --jq '[.[]|select(.state=="APPROVED")|.user.login]|unique|join(",")'
gh api repos/canton-network/splice/pulls/8062 --jq '"\(.user.login) merged \(.merged_at) by \(.merged_by.login)"'
```
```
JYC11: HTTP/1.1 404 Not Found
dfordivam: HTTP/1.1 404 Not Found
meiersi-da: HTTP/1.1 204 No Content
Co-authored-by: Simon Meier <simon@digitalasset.com>
meiersi-da,pawelperek-da
JYC11 merged 2026-11-11T02:49:34Z by dfordivam
```

## Verdict

New, family Y. Real and deterministic on the mainnet-Daml nightly from 2026-11-11 (first sha with #8062); fix in the
test: tag the test case "Grant, Update, Amend and Revoke Featured App Right" in `SvFrontendIntegrationTest.scala`
(line 585) `taggedAs Tag("org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25")`, as
TokenStandardTransferIntegrationTest does. That also covers the Amend step (section 6), which needs
splice-dso-governance 0.1.31 and is excluded by the same nightly (SpliceDsoGovernance_0_1_31 is excluded alongside).
Alternative if the Grant/Update/Revoke coverage on MainNet's packages is wanted: split off the appKind/opsParties
and Amend parts into a tagged test case. Suggested assignee meiersi-da (co-author and approver of #8062; author not
assignable). Not verified: whether the SV UI should hide appKind/opsParties while splice-amulet 0.1.24 is active
(`git show 9dd892f:apps/sv/frontend/src/components/forms/GrantRevokeFeaturedAppForm.tsx | grep -ci version` prints 0, so the form
itself has no version check; hooks and other files not searched); left to the owner, not in the comment. Canton pin not looked up (not needed).
