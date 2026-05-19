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

The binding is DSO-signed and managed exclusively through ``DsoRules``. SV
onboarding (``DsoRules_AddSv``) atomically installs the represented SV's
self-binding (``governanceVoter == sv``), so every onboarded SV has exactly
one active binding from creation. Subsequent changes flow through the
``SRARC_RotateGovernanceVoter`` action, which is dispatched by
``DsoRules_RotateGovernanceVoter`` to fetch-and-archive the current binding
and create a new one — preserving the single-active-binding-per-SV property
on the ledger. The action is operational (not in ``isGovernanceVoterAction``)
and runs through the standard confirmation-quorum flow, so a single SV
operator cannot unilaterally swap their governance voter. Returning control
to the operator is expressed as rotation back to ``governanceVoter == sv``;
the DSO party is rejected as a rotation target. There is intentionally no
``Clear`` choice — leaving the SV without a binding would leave nobody
authorized to cast its vote on governance-voter actions.

Because the DSO is the sole signatory, bare-create by the represented SV is
not authorized, and the implicit per-signatory ``Archive`` choice is only
available to the DSO. Multi-user organizations are expected to assign several
users to the single governance-voter party at the dApp/UI layer rather than
by maintaining multiple bindings. All casts write into the represented SV's
single vote slot under a per-SV cooldown, so the one-vote-per-SV tally is
robust by construction.

The represented SV is the only non-DSO observer. The governance-voter party
discovers the binding through Scan or explicit disclosure rather than as a
ledger observer, so a participant hosting the governance-voter party does not
need to vet the ``splice-dso-governance`` DAR for this template.

Operational and governance-voter actions follow a strict role split:

Both opening and casting a vote use a single choice each — ``DsoRules_RequestVote``
and ``DsoRules_CastVote``. Each choice has an optional ``bindingCid``
argument that selects the path:

* Operational actions (everything outside the allowlist below) are requested
  and voted on via the operator path: ``bindingCid = None`` on both choices.
  Each choice rejects governance-voter eligible actions on this path.
* Governance-voter eligible actions are requested and voted on via the
  governance-voter path: ``bindingCid = Some _`` (and, on the cast choice,
  ``castBy = Some <governance-voter party>``). The represented SV is
  recovered from the binding; the checked-fetch enforces that the caller
  is the binding's authoritative governance voter.

Operators have no override on the governance-voter side: there is no path by
which an operator can overwrite a governance voter's vote on an eligible
action. The operator and governance-voter paths intentionally share the
represented SV's cooldown because there is still only one vote slot per SV.

Each recorded vote carries the ``SvGovernanceVoter`` binding it was cast
under (``Vote.bindingCid``). When ``DsoRules_CloseVoteRequest`` is invoked
with the current set of live bindings (``currentBindings = Some [...]``),
the close logic builds a ``represented-SV -> live-binding`` map and drops
any governance-voter-cast vote whose recorded ``bindingCid`` is no longer
the live binding for the vote's SV — that is, the SV has rotated its
governance voter after the vote was cast. Dropped voters are reported in
``DsoRules_CloseVoteRequestResult.staleBindingVoters`` alongside the
existing ``offboardedVoters`` channel. ``currentBindings = None`` skips
the staleness check for back-compat with pre-staleness clients; the
caller is trusted to pass the complete set of live bindings, just as it
is trusted to choose which request to close.

The supported submission path is explicit disclosure: a governance voter that
is not affiliated with the represented SV presents the Scan-discovered proposal
and request contract IDs together with the necessary disclosed contracts when
exercising the cast choice. SV-hosted submission or relay remains a valid
deployment option but is not required by this design.

The dApp standard (CIP-0103) defines the client-side API between a dApp and a
Wallet rather than any on-ledger contract pattern, so it does not prescribe the
shape of these templates. The contract surface in this slice is intentionally
compatible with a CIP-0103 external-party submission flow: the cast choice is
controlled by the governance-voter party and takes ``requestCid`` and
``bindingCid`` as plain contract IDs, with the binding sourced via Scan and
supplied as a disclosed contract on the cast submission. A CIP-0103-conforming
Wallet can therefore submit the cast via ``prepareExecute`` with the relevant
disclosed contracts, and the remaining alignment work — the governance-voter
dApp client, Scan-based discovery, and Wallet/signing-provider choice — lives
downstream of this PR.

The first contract slice uses a hardcoded Daml allowlist for governance-voter
eligible actions. New ``ActionRequiringConfirmation`` constructors are rejected
by default until reviewed and added deliberately. The proposed allowlist is
intended to be concrete enough for maintainer and CIP review, not a final
statement of upstream governance policy. In particular, inclusion of
``SRARC_OffboardSv`` should be validated through that review because it is a
high-impact governance membership action.
