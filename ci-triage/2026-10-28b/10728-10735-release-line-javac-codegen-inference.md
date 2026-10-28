# 10728 / 10735 - release lines: javac fails on generated Daml codegen ("cannot infer type-variable(s) T,T,T,T,T,T")

Verdict: new family (Q), two hits on 2026-10-28 on two release lines, both cold compiles; cause not established. In
both jobs javac rejects a generated `JsonLfDecoders.record(...)` decoder in Daml Java codegen output with an inference
error between unrelated field types. The same commit compiles in every other shard of the same run, and the next run
on 0.9.x (7980b7eb9b, which contains the same change) is green, so this is not a code change breaking the build.

| Ref | Run / job | Branch, sha | Module, file | Caches |
|---|---|---|---|---|
| 10728 | 40666654116 / 122743358995 `simtime (0)` | release-line-0.9.x 3514d7e8ad (#8064) | splice-amulet-name-service, `allocationv2/Allocation_Settle.java:83` (`Map<String,V>` vs `Optional<T>`) | classes + sbt miss, daml-artifacts hit (0.9.x b156946e2d) |
| 10735 | 40676380144 / 122776222058 `wall-clock-time (2)` | release-line-0.9.3 94b51bbed3 (#8070) | splice-token-standard-utils-test, `issuance/OpenMiningRoundSummary.java:86` (`Long` vs `Optional<T>`) | all caches miss (fully cold) |

10735 is reported as a test failure but all 63 tests passed; the job fails because sbt's exit code is non-zero after
the javac error in a Daml module compiled during `testOnly`.

```
gh api repos/canton-network/splice/actions/jobs/122743358995/logs | grep -a -A3 'cannot infer type-variable'
gh api repos/canton-network/splice/actions/jobs/122776222058/logs | grep -a -A3 'cannot infer type-variable'
gh api "repos/canton-network/splice/actions/runs/40666654116/jobs?per_page=100&filter=latest" --jq '[.jobs[]|.conclusion]|group_by(.)|map("\(.[0])=\(length)")'
```
```
[error] .../splice-amulet-name-service/target/daml-codegen-java/.../allocationv2/Allocation_Settle.java:83:33: incompatible types: cannot infer type-variable(s) T,T,T,T,T,T
[error]     (argument mismatch; bad return type in lambda expression
[error]       inference variable T has incompatible equality constraints java.util.Map<java.lang.String,V>,java.util.Optional<T>)
[error] .../splice-token-standard-utils-test/target/daml-codegen-java/.../issuance/OpenMiningRoundSummary.java:86:33: incompatible types: cannot infer type-variable(s) T,T,T,T,T,T
[error]       inference variable T has incompatible equality constraints java.lang.Long,java.util.Optional<T>
["failure=1","skipped=4","success=55"]
```

Ruled out: the Daml model (the field exists on both branches), a stale classes cache (10735 had no cache at all).
Not seen in earlier release-line failures (runs 40522526310, 40307406863: no javac errors). Both hits compile several
codegen modules in parallel in one sbt JVM (in-process javac) right after codegen; next step is to check whether two
codegen runs write overlapping `daml-codegen-java` packages concurrently (shared API packages are generated into every
dependent module) or whether the release-line runner image/JDK changed today. Owner: build/CI (whoever owns the
release-line CI), not a product bug.
