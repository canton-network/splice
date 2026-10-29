# PV 36 founding fails on deployments (10844, follow-up of 10520 / #8065)

## Evidence

- ciperiodic deploy_lsu_operator base deploys on 2026-10-28 23:22 (pipeline 87538, splice dd1bb361d) and
  2026-10-29 10:30 (pipeline 87592, main), sv-1 with `canton.sv-apps.sv.onboarding.initial-protocol-version = 36`
  (cni #7746). Both contain #8065.
- sv-1 founds `global-domain::...::36-0`, then sv-1's participant fails `ReconnectSynchronizer` with
  `INITIAL_ONBOARDING_ERROR ... Unable to register onboarding topology transactions` (23:34:55, again 10:33:56),
  `SV1Initializer bootstrapping Dso failed`; all SV and validator helm releases time out.
- sequencer (23:34:54-56): the OwnerToKeyMapping of all three SV1 nodes is rejected, `SerialMismatch(expected = 2,
  actual = 1)`; the NamespaceDelegations are accepted as duplicates.

| Node | stored in own store | genesis copy | sent by the node (rejected) |
|---|---|---|---|
| participant OTK | 64e47cb7, signing 1220b09b39f9, enc 122040902936 | ef0153aa, signing 1220318bcdbe + 1220ee61b83b, enc 1220eeba7e84 | 92ee7dd3, signing 1220b09b39f9, enc 122040902936 |
| sequencer OTK | 20468c75, signing 12202dbbd3f2, enc 12204cb01e74 | 842fcd18, signing 122066377e7d + 12209263c613 | 5de6cd98, signing 12202dbbd3f2, enc 12204cb01e74 |
| mediator OTK | 93a629da, signing 122028a71d11, enc 1220983a7204 | 516ae603, signing 12202f4d2859 + 12208ca4d402 | c1c6565d, signing 122028a71d11, enc 1220983a7204 |

Logs: ~/src/splice/log/inbox-2026-10-29/sv1-*.json (host).

## Mechanism

- SV1Initializer builds the genesis identity of participant, sequencer and mediator with
  `generateOnboardingTopologyTransactions(initialProtocolVersion)` (sequencer/mediator before #8065, participant since).
- Canton `GrpcTopologyManagerReadService.generateOnboardingTopologyTransactions` returns the stored identity only if it
  already has the representative of the requested PV; otherwise `GenerateOnboardingTransactions.generate` rebuilds it in a
  temporary store, looking up keys by name (`<instance>-sequencer-auth`, `<instance>-protocol`, `<instance>-encryption`)
  and generating new keys when absent (`KeyHelper.getOrCreateSigningKey` / `getOrCreateEncryptionKey`).
- Deployments initialize nodes through splice `NodeInitializer` with keys named `namespace`, `signing`, `encryption`,
  stored at the node default PV (37). So the rebuilt genesis OwnerToKeyMappings carry new keys. Each node later sends
  its real mapping converted to PV 36 (`StoreBasedSynchronizerOutbox` / onboarding registration), which has a different
  hash than the genesis serial 1 -> SerialMismatch.
- Integration tests: Canton auto-initializes the nodes with exactly those key names, so the rebuilt mapping carries the
  real keys and founding at 36 works (why #8065 passed locally and in the nightly setup).

## Consequences

- Founding a new network with `initial-protocol-version` below the nodes' default PV fails on deployments (current
  main, release built from it). Founding at the default PV, existing networks, joining SVs and LSU are unaffected.
- #8065 fixed the integration-test variant only; its description overstates it.
- ciperiodic: founding at 36 reverted (cni ray/ciperiodic-revert-found-pv36), LSU 37 -> 37 until fixed.

## Fix direction (not decided)

Genesis must contain each node's existing identity, converted to the initial PV with the node's existing keys
(what the node itself sends). Either Canton changes `generateOnboardingTopologyTransactions` to convert the stored
identity of an initialized node instead of rebuilding it with name-based keys, or splice stops using it for
initialized nodes and builds the genesis from converted stored transactions (needs a way to have each node re-sign at
the target PV). Test must use NodeInitializer-style node initialization, not Canton auto-init.

## Texts

### Comment on 10520 (closed by #8065)

Follow-up: #8065 fixes the integration-test variant only. On a real deployment (ciperiodic, sv-1 with
onboarding.initial-protocol-version = 36, base deploys 10-28 23:22 and 10-29 10:30, both with #8065) founding still
fails with INITIAL_ONBOARDING_ERROR: the genesis OwnerToKeyMappings of participant, sequencer and mediator carry newly
generated keys, because Canton's generateOnboardingTopologyTransactions rebuilds a node's identity with keys looked up by
Canton's own names when the stored identity is at another PV, and splice's NodeInitializer names them differently.
Tracked in #10844, details in ci-triage/2026-10-29/pv36-founding-deployments.md (ray/ci-triage-2026-09-15).

### Comment on canton-network/splice#8065

Note: this fixes the PV 36 founding failure of the integration tests only. On deployments, where splice's
NodeInitializer creates the node keys, generateOnboardingTopologyTransactions(36) rebuilds the participant's,
sequencer's and mediator's identity with newly generated keys, and the nodes' own (converted) OwnerToKeyMappings are
then rejected with SerialMismatch. Seen on ciperiodic on 10-28 and 10-29; see DACH-NY/cn-test-failures#10844.

### Canton issue

Title: generateOnboardingTopologyTransactions creates new keys for an initialized node whose identity is stored at
another protocol version

When a node's identity transactions in the Authorized store do not have the representative of the requested protocol
version, `TopologyManagerReadService.GenerateOnboardingTopologyTransactions` (GrpcTopologyManagerReadService ->
GenerateOnboardingTransactions.generate) rebuilds the identity in a temporary store. It looks up the node's keys by name
(`<instance>-sequencer-auth`, `<instance>-protocol`, `<instance>-encryption`) and generates new keys when no key has
that name. For nodes initialized manually with differently named keys, the returned OwnerToKeyMapping therefore lists
keys the node never uses. Splice uses the call to build the genesis state of a synchronizer founded below the nodes'
default protocol version (here PV 36 with nodes at 37); the nodes then publish their real OwnerToKeyMapping (converted
to 36 by the outbox) and the synchronizer rejects it with SerialMismatch(expected 2, actual 1), so the participant fails
with INITIAL_ONBOARDING_ERROR. Expected: for an initialized node, return its existing identity converted to the
requested protocol version (same mappings and keys, re-signed), as StoreBasedSynchronizerOutbox's convertTransactions
does, or fail instead of generating keys.
