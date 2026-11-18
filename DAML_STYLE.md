# Daml Style Guide

Apply these rules to code you write or change. Don't rewrite existing code only to follow them, as that adds review noise.

## Future Proofing

Released Daml can only change in ways that are compatible with smart contract upgrading (SCU), as described in the [Smart Contract Upgrading Reference](https://github.com/canton-network/cf-docs/blob/main/docs-main/appdev/deep-dives/smart-contract-upgrading-reference.mdx). In particular, records only gain `Optional` fields at the end, variants only gain constructors at the end, enums only gain constructors if all their constructors are nullary, and templates, choices, fields and constructors can be neither removed nor renamed. Write code that can evolve within these limits, not code that is more general than needed.

* Don't add choices, fields or parameters without a concrete need, as they cannot be removed once released. Prefer one choice with an `Optional` argument over several near-duplicate choices.
* Give every new choice its own result record and every contract key its own record, even if they have no or only one field: unlike a bare type or an enum, a record can gain fields later. Declare a record without fields as `data Foo = Foo {}`; `data Foo = Foo` declares an enum.
* Derive `Serializable` only for types that templates, choices or interface views need, as only serializable types are bound by SCU.
* Make the compiler flag code that must handle new fields or constructors:
  * Use `{..}` and `{field}` record patterns cautiously: they silently ignore fields that are added later. Where every field must be processed, match positionally and name unused fields `_field`. `{..}` on test setup records such as `DefaultAppWithUsers` is fine.
  * Avoid catch-all branches (`_ -> ...`) in `case` expressions: a constructor added later silently takes them instead of being flagged as unhandled. Use one only if it saves a lot of code and is correct for any future constructor.

## Templates and Choices

* Validate inside the choice body, not only in helpers that exercise the choice.
* Treat contracts read from the ledger like untrusted user input: validate that the fields of every contract that a choice looks up, fetches, exercises or archives are as the context expects, e.g. its `owner`, its `dso` party or its `instrumentId`. Its signatories vouch for its contents, but not for it being the contract that the context refers to.
  * Use `fetchChecked`; for templates without a `HasCheckedFetch` instance, add one if the template has a single owning party, otherwise fetch the contract and check the relevant fields. Comment on each `potentiallyUnsafeArchive` why it is safe.
* Assert the preconditions that a choice relies on with `require`, and the invariants of a template with `ensure`.
  * To compare two values in `Update` code, use `require'` with a relation such as `isEqualR` or `isGreaterR`, or `requireMatchExpected`, from `Splice.TokenStandard.Utils`: their error messages name and show both values, e.g. `require' ("amount", amount) isGreaterR ("zero", 0.0)`.
* Fail early: use `require`, `fail` or `error` instead of threading `Optional` or `Either` through `Update` code or falling back to defaults. Guard impossible cases explicitly.
* New feature flags in Daml config default to off, so that voting in a package does not enable the feature. Name them as described in [Config Parameters](AGENTS.md#config-parameters).

## Types and Names

* Name recurring tuples and argument groups with a record type or type synonym.
* Give each constructor of a variant its own record type if some functions handle only one of them.
* Within a module, follow its existing naming scheme; apply the naming rules below where it has none.
* Spell out names; avoid single letters and non-standard abbreviations.
* Name choices `<Template>_<Verb>`, their result records `<Choice>Result` and contract key records `<Template>Key`, e.g. `AmuletRules_BuyMemberTraffic` and `AmuletRules_BuyMemberTrafficResult`. CI checks the result type names (see [Checks](#checks)).

## Readability

Write Daml so that developers who know mainstream languages, but not Haskell, can read it.

* Prefer `do` blocks, `case` expressions, explicit lambdas and named functions (`void`, `forA`, `mapA`) over Haskell operators such as `<$`, `$>`, `<*>`, `>>=` and `=<<`.
* As an exception, `<$>`, `$` and `<>` are fine.
* Use point-free composition with `.` only where it makes the logic clearer to a reader.
* To discard a result, use `void` or end a `do` block with `pure ()`.
* Use `case` expressions, or one definition per constructor, instead of fold-style helpers.
* Instead of passing continuations or function parameters that are called only once, return a data type that the caller can `case` on.
* Inline helpers that are used only once or whose type signature is longer than their body.

## Records

Build records with Daml's `with` syntax, not Haskell's braces.

* Construct and update records with `with`, e.g. `Transfer with sender; amount = 1.0` and `transfer with amount = 2.0`.
* Pun fields whose value is a variable of the same name: `with owner; amount`, not `with owner = owner; amount = amount`.
* Use `with ..` to fill a record from same-named variables only for the result record at the end of a choice, e.g. `return AmuletRules_MintResult with ..`.
* Read single fields with dot syntax, e.g. `transfer.amount`, instead of destructuring the record (see [Future Proofing](#future-proofing)).
* Where a pattern names fields, e.g. to match on a field's value as in `Transfer{lock = None}`, use braces, not `with`: brace patterns work in every position without parentheses.
* Put one field per line; short field lists may share a line, e.g. `ForOwner with dso; owner = provider`.

## Documentation

Doc comments in non-test Daml packages are published as the Daml API reference (see `docs/gen-daml-docs.sh`). Write them for app developers and auditors, and only where they add information.

* Document each template, choice, interface and serializable type you add, and give modules that define them a `-- |` header. Say who acts, what it is for, and which invariants and lifecycle the code enforces.
* Document a field or argument only if its name and type leave something open: units, the meaning of `None` or `[]`, time semantics, constraints, or who sets it. E.g. `-- ^ Absolute, inclusive deadline as of which the lock expires.`, but not `-- ^ The amount.`
* On internal helpers, comment only on caller obligations ("Callers MUST ...") and non-obvious choices. Explain why, not what the code does.
* Where a definition implements a CIP, link to the relevant section instead of restating it.

## Tests

* Test every new or changed choice in Daml Script, including its failure cases with `submitMustFail`.
* In Daml Script tests, compare whole values with `===` instead of checking fields one by one.
* Use uneven amounts and splits, so that swapped arguments or contract ids make the test fail.

## Checks

Run these checks after changing Daml code. CI runs them as part of the static checks. Run them in the direnv shell, e.g. with `direnv exec .`, rather than via sbt to avoid its startup time.

* `./scripts/check-daml-return-types.sh` checks that each choice `X` returns `XResult`.
* `./scripts/rename.sh no_illegal_daml_references` rejects disallowed words and patterns in Daml code and comments, e.g. `coin` or `domain`, mentions of `DSO` other than e.g. "DSO party" or `DsoRules`, and GitHub links other than to CIPs; see the script for the full list. sbt runs it as `illegalDamlReferencesCheck`, part of `sbt lint`.
