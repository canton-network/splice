# 11232 - ManualSignatureIntegrationTest: validator OTK check gives up before the validator reaches the rotation (run 42106471025)

main 64cbca6, job 127524013040 `signatures (0)`, attempt 1. Artifact logs-signatures-0 (13451304745) in log/11232/art.
Component: test timing. First hit (search `ManualSignatureIntegrationTest`: 10438, 10490, 9831, 8849, 6502, all other
signatures).

## 1. Failure

```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/11232/job.log | grep -a -A1 'OTK mapping keys that are not signed. \*\*\* FAILED'
```
```
[info] - should rotate validators' OTK mapping keys that are not signed. *** FAILED ***
[info]   List(12205abedcdb..., 122004aeaf70...) was not empty keys without signatures (ManualSignatureIntegrationTest.scala:47)
```

## 2. Timeline (canton_network_test.clog.gz)

```
zcat canton_network_test.clog.gz | jq -r 'select(.message|test("Running clue: Alice validator starts|Finished clue: Alice validator starts|Running clue: keys are rotated for alice|Vet packages finished|Test failed|checking if OTK rotation|need to be rotated")) | ...'
```
```
21:01:43.398 Running clue: Alice validator starts.
21:01:43.856 Finished clue: Alice validator starts.
21:01:46.335 Running clue: keys are rotated for alice validator's participant.
21:02:23.900 Test failed: 'ManualSignatureIntegrationTest/synchronizer should rotate validators' OTK mapping keys that are not signed.', message: List(12205abedcdb..., 122004aeaf70...) was not empty keys without signatures, location: SeeStackDepthException
21:02:23.935 aliceValidator app initialization: Vet packages finished after PT37.195150177S
21:02:25.453 Node is initialized with identity aliceValidator::12200bea6109..., checking if OTK rotation is needed
21:02:25.464 The following keys (likely created on a version before 0.3.1) need to be rotated because of missing signatures: List(12205abedcdb..., 122004aeaf70...)
```
The test starts alice with `aliceValidatorBackend.start()` (not `startSync()`) and polls with `eventuallySucceeds(40.seconds)`
(lines 84-100). ValidatorApp init runs `ParticipantInitializer.ensureInitializedWithRotatedOTK` (ValidatorApp.scala:686)
after the Vet packages step; that step uploaded 79 DARs (21:01:46.765 to 21:02:21.911, about 4 s per batch of 5) and took
37.2 s, so the rotation check ran 1.5 s after the test's deadline and found exactly the two keys the test reported.

Not verified: how long Vet packages takes in a passing run (passing jobs upload no artifact), so whether 37 s is a
slow runner or the normal duration grown with the number of DAR versions is open. Either way the 40 s budget covers
the whole validator init, not the rotation.

## Verdict

New, test timing. Fix (test): `aliceValidatorBackend.startSync()` before the check, as the SV case does with
`sv1Backend.startSync()`; the check can then keep a short eventually. Owner: julientinguely-da (#1912, git blame of
lines 80-101 is all that commit).
