# Group burst: 11207 11208 11211 11212

Packet: `evidence/11207-11208-11211-11212-release-line-burst.md`.

| Ref | Verdict | Cause | Rank |
|---|---|---|---|
| 11207 | root cause (missing backport) | #7909 treasury deadline 10 s -> 30 s not on 0.9.4/0.9.x/0.9.3; tap 429 SUBMISSION_ALREADY_IN_FLIGHT | rare: first release-line hit, under burst load |
| 11208 | duplicate of 10358 | sbt 40 min kill, from-scratch compile (release lines always miss classes cache) | frequent family, owned |
| 11211 | root cause (ignore gap) | `BFT orderer can't connect to database` variant of the #7643 ignore line | rare: first hit, 5 s pool exhaustion under load |
| 11212 | duplicate of 10358 | as 11208 | as 11208 |

Lesson: none.
