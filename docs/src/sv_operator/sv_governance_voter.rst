..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

SV Governance Voter Prototype
==============================

The governance-voter contract work is a prototype for review under
`Canton Development Fund PR #223 <https://github.com/canton-foundation/canton-dev-fund/pull/223>`_,
especially Milestone 1: Governance-Voting Identity and CIP.

Phase 1 preserves the current one-vote-per-SV model. A governance voter is
intended to be an alternate signer for the represented SV's vote on explicitly
supported non-operational governance actions; it is not a new voting unit and
does not add voting weight.

Vote records carry the represented SV and the party/role that cast the vote.
This attribution is accountability metadata for the authority path; tallying
continues to use the represented SV's vote slot.
Vote slots are keyed by represented SV party, not by SV display name. This keeps
cooldown and overwrite behavior tied to the stable party identifier while
existing review-facing outputs can still render SV names.

The prototype binding is declared by the represented SV and has no contract key.
The intended invariant is one active governance-voter binding per SV. This slice
keeps that invariant outside the template key space by design, matching the
Phase 1 proposal direction.

The binding is SV-declared by design: the represented SV can create, rotate, or
clear its governance-voter binding without a Propose-Accept step. Self-binding
(``governanceVoter == sv``) is also allowed by design for bootstrap and
self-voting. The DSO party cannot be used as a governance voter.

Governance-voter vote submission fetches the binding by contract ID, validates
the signer against the binding, checks the action allowlist, and writes the vote
into the represented SV's vote slot. It cannot overwrite a prior operator-cast
vote for that SV; operator votes remain the precedence path. The operator and
governance-voter paths intentionally share the represented SV's cooldown because
there is still only one vote slot per SV.

The supported submission path is explicit disclosure: a governance voter that
is not affiliated with the represented SV presents the Scan-discovered proposal
and request contract IDs together with the necessary disclosed contracts when
exercising the cast choice. SV-hosted submission or relay remains a valid
deployment option but is not required by this design.

Alignment with the dApp standard (CIP-0103) is still an open review item. The
contract surface — fetching the binding and request by contract ID, no contract
key on the binding, the action allowlist gate, and explicit ``castBy`` /
``castByRole`` attribution — was chosen to be compatible with an
explicit-disclosure flow, but a deliberate review against CIP-0103 is needed
before this prototype is promoted out of draft.

The first contract slice uses a hardcoded Daml allowlist for governance-voter
eligible actions. New ``ActionRequiringConfirmation`` constructors are rejected
by default until reviewed and added deliberately. The proposed allowlist is
intended to be concrete enough for maintainer and CIP review, not a final
statement of upstream governance policy. In particular, inclusion of
``SRARC_OffboardSv`` should be validated through that review because it is a
high-impact governance membership action.
