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
the party authorized to act on the represented SV's vote on explicitly
supported non-operational governance actions; it is not a new voting unit and
does not add voting weight.

Vote records carry the represented SV and the party/role that cast the vote.
This attribution is accountability metadata for the authority path; tallying
continues to use the represented SV's vote slot.
Vote slots are keyed by represented SV party, not by SV display name. This keeps
cooldown and overwrite behavior tied to the stable party identifier while
existing review-facing outputs can still render SV names.

The prototype binding is declared by the represented SV and has no contract key.
The intended Phase 1 workflow has one active governance-voter party per
represented SV, shaped by the consuming ``RotateGovernanceVoter`` choice and
the self-binding onboarding default. Multi-user organizations are expected to
assign several users to the single governance-voter party at the dApp/UI
layer rather than by creating additional bindings. All casts write into the
represented SV's single vote slot under a per-SV cooldown, so the
one-vote-per-SV tally is robust regardless of how the off-ledger workflow is
arranged.

The template does not contract-level prevent the represented SV from
bare-creating additional bindings for itself: ``sv`` is the sole signatory,
and the consuming rotation is intent rather than enforcement. If a represented
SV does end up with two active bindings, both delegates can cast against the
same represented-SV slot under last-writer-wins; the tally still records one
vote per represented SV, but the cast log becomes ambiguous. This boundary is
pinned by ``testGovernanceVoterDuplicateBindingsAmbiguity`` so that any future
tightening — contract key, DSO-owned registry, or explicit duplicate-create
guard — has a concrete baseline to compare against. Whether to promote the
workflow shape to a contract-level invariant is left as an open question for
Splice maintainers.

The binding is SV-declared by design: the represented SV can create or rotate
its governance-voter binding without a Propose-Accept step. The onboarding
default is self-voting (``governanceVoter == sv``); returning control to the
operator is expressed as ``RotateGovernanceVoter`` back to the represented SV
itself. There is intentionally no Clear choice — leaving the SV without a
binding would leave nobody authorized to cast its vote on governance-voter
actions. The DSO party cannot be used as a governance voter.

The implicit per-signatory ``Archive`` choice still lets the represented SV
unilaterally archive a binding, since the SV is the sole signatory. This is
self-harm only — the SV temporarily loses the ability to cast on
governance-voter actions and recovers by creating a new self-binding — and the
Phase 1 expectation is that the SV workflow uses ``RotateGovernanceVoter``
rather than direct archive. Hard enforcement of the "every SV has at least one
active binding" invariant is left for a later phase if maintainers want it.

Operational and governance-voter actions follow a strict role split:

* Operational actions (everything outside the allowlist below) are requested
  and voted on by the operator via ``DsoRules_RequestVote`` and
  ``DsoRules_CastVote``. Both choices reject governance-voter eligible actions.
* Governance-voter eligible actions are requested and voted on by the
  governance-voter party — which is the represented SV itself under the
  default self-binding — via ``DsoRules_RequestGovernanceVote`` and
  ``DsoRules_CastGovernanceVote``. ``DsoRules_CastGovernanceVote`` checks the
  binding and rejects calls for actions outside the allowlist.

Operators have no override on the governance-voter side: there is no path by
which an operator can overwrite a governance voter's vote on an eligible
action. The operator and governance-voter paths intentionally share the
represented SV's cooldown because there is still only one vote slot per SV.

The supported submission path is explicit disclosure: a governance voter that
is not affiliated with the represented SV presents the Scan-discovered proposal
and request contract IDs together with the necessary disclosed contracts when
exercising the cast choice. SV-hosted submission or relay remains a valid
deployment option but is not required by this design.

The dApp standard (CIP-0103) defines the client-side API between a dApp and a
Wallet rather than any on-ledger contract pattern, so it does not prescribe the
shape of these templates. The contract surface in this slice is intentionally
compatible with a CIP-0103 external-party submission flow: the cast choice is
controlled by the governance-voter party, takes ``requestCid`` and
``bindingCid`` as plain contract IDs, and the binding is observable by the
governance voter so it can be supplied as a disclosed contract. A
CIP-0103-conforming Wallet can therefore submit the cast via ``prepareExecute``
with the relevant disclosed contracts, and the remaining alignment work — the
governance-voter dApp client, Scan-based discovery, and Wallet/signing-provider
choice — lives downstream of this PR.

The first contract slice uses a hardcoded Daml allowlist for governance-voter
eligible actions. New ``ActionRequiringConfirmation`` constructors are rejected
by default until reviewed and added deliberately. The proposed allowlist is
intended to be concrete enough for maintainer and CIP review, not a final
statement of upstream governance policy. In particular, inclusion of
``SRARC_OffboardSv`` should be validated through that review because it is a
high-impact governance membership action.
