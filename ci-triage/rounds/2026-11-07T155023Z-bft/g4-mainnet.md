# g4-mainnet: 11274 11275 11276 (run 42341066277, nightly mainnet Daml, main 0b2ccec235)

Packet: evidence/11274-11275-11276-mainnet-nightly-tag-and-lost-verdict.md
Comments: comments/11274.md, comments/11275.md; 11276 closed as dup of 11275 (inline comment).

- 11274: new, hard broken (every nightly) - SpliceAmulet_0_1_25 tag on abstract base class not inherited (#8224); sv1 bootstrap fails on extraFeaturedAssetActivityWeight downgrade. Assignee adetokunbo (#8224 author).
- 11275: new (B trigger) - verdict for request 00:00:00.236Z lost (sv4 mediator refused by blacklisted sequencer, sv1 mediator never sent, threshold 2); decision timeout in sim time never reached; all SV participants stop publishing from 05:32:44. #8356 removes the trigger. Open: sv1 mediator missing send.
- 11276: dup of 11275 (sv4 blacklisted 05:37, 60 MED::sv4 refusals, sv4Participant indexer stops 05:36:57, sv4Validator init timeout); stuck request not pinned.

Lesson candidate: scalatest class tags on an abstract base class are silently ignored (tag annotations are not @Inherited) - put tags on concrete suites.
