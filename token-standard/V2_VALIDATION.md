## Token Standard V2 Daml API Validation

The V2 Daml APIs are ready for initial validation. A small number of minor cleanups remain, but these are **not expected to block validation efforts**.

Please provide feedback on this [PR that shows the V2 API changes relative to V1](https://github.com/hyperledger-labs/splice/pull/4562/changes), or via Slack/IM/Email.


### Scope

This validation phase aims to confirm that the V2 APIs can be correctly implemented and used in both token registries and trading applications, including mixed V1/V2 settlement scenarios.
It aims to do so by writing Daml script tests that mirror real-world use cases and workflows, using the provided test packages as a reference.

### Required Actions

**Token registry developers**

* Implement the V2 APIs in your token registry.
* Validate correctness by running TokenDvP-style tests against your implementation (e.g., analogous to `Splice.Tests.TestAmuletTokenDvP_V1V2Mixed`)

**Trading application developers**

* Adapt core workflows to use the V2 APIs.
* Verify that mixed-version (V1/V2) settlement works as expected.
* Use `Splice.Tests.TestAmuletTokenDvP_V1V2Mixed` and `Splice.Testing.Apps.TradingAppV2` as a reference and adapt them to your application.

### Setup

**Important:** base all your actions on the
   [`token-standard-v2-daml-preview` branch](https://github.com/hyperledger-labs/splice/tree/token-standard-v2-daml-preview)

1. Copy the non-test DARs from `/daml/dars/` in the into your validation project.
2. Copy the Daml script test packages from the same branch **as source code** to avoid cross-SDK issues.
3. Use the following as blueprints and adapt them to your asset and workflows:

   * `Splice.Tests.TestAmuletTokenDvP_V1V2Mixed`
   * `Splice.Testing.Apps.TradingAppV2`

### Expected Changes

* The API surface is considered stable enough for validation, but small refinements may still occur.
* Test coverage and example implementations will continue to improve.

---

## Appendix: Planned Cleanup (Non-blocking)

Cleanup and improvements applied so far:

* Replace `ChoiceExecutionMetadata` with concrete result types for `AllocationRequest_Reject`
  and `AllocationRequest_Withdraw` choices to prepare for an eventual future where interface definitions
  may be upgraded
- Use `authorizerHoldingCids` instead of `senderHoldingCids` in all V2 choice results that
  return holdings of the allocation authorizer
- Add an explicit `AllocationRequest_Accept` choice to provide a standard way for wallets to signal acceptance
  and provide replay protection for the creation of the corresponding allocations
- Add a new `Splice.Util.TokenWallet.BatchingUtilityV2` template with choices that implement the standard
  logic for accepting V1 and V2 requests in a V2 wallet.
- Reordered the `HoldingV2.Account` fields to put `owner` first for improved readability of debug output
- Return "holding change" as `TextMap [ContractId Holding]` where the keys are `instrumentId.id`s, so that
  callers can identify the holdings for a specific instrument without needing to fetch the holding.
- Replace buggy `netAllocationCreditAmount` with `netAllocationCreditAmounts` that properly distinguishes
  between legs of different instruments, and a map of credit amounts by instrument id.
- Export 'require' and 'isGreaterOrEqualR' and its variants from `Splice.TokenStandard.Utils` to simplify
  writing validation code in choice bodies.
- Extend token standard test infrastructure:
  - Add `TestTokenV1` to simulate settlement involving V1-only tokens
  - Add `TestTokenV2` to simulate settlement involving tokens with support for
    accountable holdings and multiple instruments maintained by the same `admin`
  - Add `MultiRegistry` to simulate the off-ledger APIs of multiple registries in a single test environment
  - Extend `TradingAppV2` to support mixed version settlement of trades involving V1-only tokens, and V1/V2 tokens
    whose allocations are created through either a V1 wallet or a V2 wallet
- Remove redundant `Splice.Testing.UtilsV2` module: use `Splice.Testing.Utils` instead
- Improve commentary on `V2.AllocationRequest` choices
- Add missing choice observers to `V2.TransferFactory_Transfer`
- Renamed `_extraObserverDefaultImpl` to `_extraObserversDefaultImpl` to reflect that it can return multiple observers
- Clarify that input holdings for `V2.TransferFactory_Transfer` must be of the transferred instrument
- Extend test infrastructure:
  - Add transfer support for `TestTokenV2`
  - `WalletClientV2` support for listing and accepting V1 and V2 transfer offers using the same functions
- Extend `BatchingUtilityV2` with support for accepting V2 transfer instructions
- Add utility functions to create metadata for [V1 transaction history parsing](https://docs.digitalasset.com/integrate/devnet/exchange-integration/txingestion.html#differences-between-1-step-deposits-and-withdrawals)
  to `Splice.TokenStandard.Utils`
- Bump the Daml SDK for building the V2 API packages to 3.4.11
- Change default implementation of `SettlementFactory_SettleBatch` to check uniqueness of transfer leg ids
  to provide better safety guarantees for code that identifies legs by their ids
- add default implementations for extra observers for `SettlementFactory_SettleBatch`  for public and private assets
- use `Account.id : Text` instead of `Optional Text` to ensure that wallets do not have to deal with
  two different kinds of defalt accounts (empty string vs None). Use "" as the default account identifier.
- Change version numbers of `splice-api-token-*-v2` packages to `1.0.0` to be consistent with the existing
  `splice-api-featured-app-v2` package, which also has version `1.0.0`
- *Drop the need for `extraSettlementAuthorizers` and `extraReceiptAuthorizers`* to use
  allocations created using `V1.AllocationFactory_Allocate` in a V2 settlement.
  - **motivation**: the extra actors on `SettlementFactory_SettleBatch` made it impossible to use V1 allocation with privacy,
    which was discovered by app providers attempting to implement the compatibility mode.
  - **key changes**:
    - The `V2.Allocation_Settle` choice always only requires authorization from the `executors` and
      the instrument `admin`. Apps can thus call `V2.SettlementFactory_SettleBatch` using `executors`
      authority only.
    - Apps need to create missing receipt allocations using their own delegation contracts from their
      traders. See the `TradingAppV2` implementation for an example of how to do this. Also note the
      use of `Splice.TokenStandard.Utils.ensureIsReceiptAllocation` to check
      that the allocation factory call delegation is for a receipt allocation only.
    - Asset owners must be aware that allocations created using `V1.AllocationFactory_Allocate` can
      be settled with only `executor` authority. They must only create allocations for `executors`
      that they trust to atomically settle trades involving their allocations.
- Introduce and implement event based reporting of all holdings changes and transfers:
  - motivation:
    - decouple the logic of executing a transfer from reporting it to external systems
    - simplify parsing for wallets: just look for `EventLog_HoldingsChange` events to understand all changes
      to the holdings of an account
  - add new API package `splice-api-token-transfer-events-v2`
  - add `Splice.Api.Token.TransferEventsV2` module
  - add `Splice.TokenStandard.Utils` convenience functions to log events for burns, mints, and transfers
  - emit `EventLog_HoldingsChange` events for all amulet choices, including properly tagging burns from ANS payments
    - includes adding metadata arguments for `AmuletRules.TransferOutput` and `TransferPreapproval_SendV2` choices
    - includes adding a `reason` argument for `AmuletRules_Mint`
    - includes populating the above arguments for both the locking and payments steps of
      the legacy ANS payment and subscription workflows in `splice-wallet-payments`
      and the legacy `TransferOffer` workflow in `splice-wallet`
  - emit `EventLog_HoldingsChange` events for `TestTokenV2` choices
- Introduce `AllocationRequest.originalRequestId` and `Allocation.originalAllocationId` fields, which
  can be used to track the same request or allocation across state updates,
  analogously to the `originalInstructionCid` of transfer and allocation instructions.
- Improve commentary on `AllocationRequest_Accept` to call out that there is no guarantee that
  wallets call the choice, and thus apps must clean up allocation requests independently
- Add `Monoid` instance for `Splice.Testing.Utils.OpenApiChoiceContext` to simplify writing tests
- Extend the token metadata API so instrument metadata can expose a `paused` flag and optional `pauseInfo`,
  allowing wallets and other clients to detect when an instrument is paused and why.
  This was done as a backwards-compatible change to `token-metadata-v1.yaml` to
  allow both V1 and V2 clients to benefit from this information without needing
  to upgrade to a new version of the metadata API.
- Add support for managing jointly controlled accounts via a token standard wallet
  - the general approach is that the other party must use the same action
    to confirm their agreement to an action. The state of pending actions is tracked
    in the `TransferInstruction`, `AllocationInstruction`, or `Allocation` contracts.
  - generalize `TransferInstruction_Accept` to accept the transfer as someone that needs
    to authorize it; e.g., the account provider of the sender might can use this
    choice to confirm that they agree to the transfer being offered to the recipient
  - add `AllocationInstruction_Accept` choice to confirm the creation of an allocation
  - validate the API on `TestTokenV2` by adding support for a rich variety of
    authorization configurations
- Use a uniform `AllocationResult` for `Allocation` choices to simplify working with them
- Add iterated settlement support across the V2 API packages so the allocation authorizer
  can authorize net funding up front, allow the executors to finalize the
  concrete transfer-leg sides at settlement time, and optionally carry reserved funding forward into later settlement
  iterations.
  - this enables use cases such as prefunding RFQ trades, and funding liquidity pools across multiple settlement iterations.
  - `splice-api-token-allocation-request-v2`:
    - add `RequestedAllocation` with `admin`, `transferLegSides`, `nextIterationFunding`, `committed`, and `meta`
    - change `AllocationRequestView.transferLegs` to `AllocationRequestView.allocations : [RequestedAllocation]`
    - move `requestedAt` and `settleAt` onto `AllocationRequestView`
  - `splice-api-token-allocation-v2`:
    - remove `requestedAt` and `settleAt` from `SettlementInfo`
    - change `TransferLeg.instrumentId` from `HoldingV2.InstrumentId` to plain `Text`, and add `TransferSide` / `TransferLegSide`
    - extend `AllocationSpecification` with `admin`, `transferLegSides`, `nextIterationFunding`, `committed`, and `meta`
    - extend `AllocationView` with `createdAt`, and `numIterations`
    - add `FinalizedAllocation`
    - extend `Allocation_Settle` with `extraTransferLegSides`, and `nextIterationFunding`
    - extend `Allocation_SettleResult` with `nextIterationAllocationCid`
  - `splice-api-token-transfer-events-v2` and token-standard utilities:
    - switch transfer and holdings-change reporting to `transferLegSides`
    - remove `TokenStandardUtils.allocationAdmin`, which is now trivial from `allocation.admin`
- Introduce committed allocations that lock the funds until settlement time.
  - `RequestedAllocation.committed` lets an app request creation of a committed allocation.
  - `AllocationSpecification.committed` records that commitment on the created allocation.
  - if `committed = True`, the authorizer cannot withdraw the allocation before the
    settlement deadline; if there is no settlement deadline, the authorizer cannot withdraw it at all.
    The allocation must instead be concluded by settlement, cancellation, or registry-specific expiry.
- Remove the `defaultAllocation_*Controllers` helper functions, as the default controller
  sets have become straightforward enough to inline in implementations.
- Introduce the notion of "special accounts" with `Account.owner = None`, which are under the control
  of the instrument admin. These are intended to be used by registries to report
  burns and mints as transfers. Registries can also use them for other purposes like for example
  allowing allocations to refer to an "anonymous settlement counterparty
  account" to enable trade settlement without disclosing the identity of the
  settlement counterparties.
  - adjust `TokenStandardUtils.netAllocationCreditAmounts` to not compute credits for the burn account as a receiver
    and neither compute debits for the mint account as the sender
  - test delivery-vs-burn and delivery-vs-mint scenarios using `TestTokenV2`
- Add an `expiresAt` field to transfer instruction and allocation instruction, so that registries
  can report and enforce a TTL for instructions that is shorter than the `executeBefore` or the `settlementDeadline` of the underlying transfer or allocation. Registries are expected to
  bump that TTL on updates to the instruction, so that only long times of inactivity lead to
  expiry.
- Improve the representation of the `availableActions` maps of `Allocation`, `AllocationInstruction`, `AllocationRequest`, and `TransferInstruction`
  - invert its type from `[Party] -> [Action]` to `Action -> [[Party]]` as that makes it easier to determine who can execute an action
  - remove the `description` and `meta` fields from the `_Custom` actions, as they bloat the representation and can be delivered
    out of band (or in the view's overall metadata) by the registry if needed.
- Polished documentation of Daml and HTTP APIs for the V2 token standard
  - introduced the `409` reponse code to report in-flight reassignments in the
  OpenAPI specs of the off-ledger APIs
