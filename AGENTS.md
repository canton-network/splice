# Agent Instructions

This document provides instructions for AI coding agents (GitHub Copilot, Claude Code, and others) to follow when assisting with development in this repository.

## Testing

Every contribution must be tested in an automated test. For further details see the [Testing README](TESTING.md).

## Building and Running Tests

* Run `sbt` inside the direnv/nix dev shell, e.g. `direnv exec . sbt -batch "apps-scan/testOnly <FullyQualifiedSuite>"`. `-batch` avoids the interactive prompt; redirect output to a file and grep for `^\[error\]` and `Tests:`.
* Test code compiles with `-Xfatal-warnings` and `-Wunused:privates`/`-Wunused:params`, so an unused private member or parameter fails `Test/compile`.
* To format specific files: `sbt "scalafmtOnly <files...>"`.
* Integration tests under `apps/app/src/test` need a running Canton from `./start-canton.sh`, including suites based on `IntegrationTestWithIsolatedEnvironment`. Without it they fail with `java.io.FileNotFoundException: canton.tokens`.
  * Start only what the test needs: `./start-canton.sh -d -w` for wallclock tests, `-s` for simtime tests. Add `-p local` to use a local Postgres instead of Docker.
  * `start-canton.sh` manages Postgres itself; do not start it separately with `scripts/postgres.sh`. Stop with `./stop-canton.sh` (pass the same Postgres mode, e.g. `./stop-canton.sh local`).
  * Restart Canton (`./stop-canton.sh` then `./start-canton.sh`) whenever `nix/canton-sources.json` changes, e.g. after a checkout or rebase: it pins the Canton version that `start-canton.sh` runs.
  * Run a single test with `sbt "apps-app/testOnly <FullyQualifiedSuite> -- -z \"<test name>\""`.
* If dependency resolution fails with a 404 for a Canton snapshot artifact (e.g. `com.daml:bindings-java:<version>-snapshot...`), the pinned snapshot has likely expired; rebase onto the latest `main` and reload direnv.

## DB Migrations

Refer to [the main README on migrations](apps/common/src/main/resources/db/migration/README.md).

## Daml Changes

### Daml Lock Files

If you make intentional changes in Daml code, run `sbt damlDarsLockFileUpdate` and commit the updated `dars.lock` file along with your dar changes.

### Backwards-compatible Daml changes

All Daml changes must be backwards-compatible. See the [Smart Contract Upgrading Reference from the Canton docs](https://docs.canton.network/appdev/deep-dives/smart-contract-upgrading-reference).

When adding to enums, make sure to only add further nullary constructors to types that only have nullary constructors.

### Daml Numerics

- To represent Daml `Numeric`s for any user facing APIs (console commands), use `scala.math.BigDecimal`.
- To represent Daml `Numeric`s in Protobuf, use `string`s. Conversions to and from `string`s should occur via `org.lfdecentralizedtrust.splice.util.Proto.encode/tryDecode`.
- When interacting with the Ledger API, convert Scala `BigDecimal`s to Java `BigDecimal`s.
- Refer to the `wallet.tap` command implementation for the canonical handling of Daml Numerics.

## Message Definitions

* All Protobuf definitions must use `proto3`.
* Avoid wrapping primitive types in a message structure unless future extensibility will likely be required.
* Use a plural name for `repeated` fields.
* Use `string` fields with a suffix `contract_id` to store contract ids.
* Use `string` fields with a suffix `party_id` to store party ids.

## Config Parameters

* Name flags as `enableXXX` instead of `disableXXX` to avoid a double negation.

## Code Layout

* Place `.proto` files in `src/main/protobuf`.
* Prefer having a single `.proto` definition per service.
* Refer to generated Protobuf classes with a package prefix, e.g., `v0.MyMessage` instead of `MyMessage`.

## Domain Specific Naming

* Use `listXXX`, `acceptXXX`, `rejectXXX`, `withdrawXXX` for managing proposals, requests etc.
* Use the term `amount`, not `quantity` or `number`.
* Use `sender`/`receiver`, not `payer`/`payee`.

## Frontend Code

Frontend code projects are managed via `npm workspaces`.

### New Packages

To add a new package to the workspace:
1. Register its directory in the root-level `apps/package.json` workspaces key. The directory must contain its own `package.json`.
2. Add the new package to `build.sbt`.
3. Ensure the package contains at least the scripts `build`, `fix`, `check`, and `start`.
4. The new package will need its own `tsconfig.json` file that inherits from the root tsconfig.

### Common Libs

The `common-frontend` package (`@canton-network/splice-common-frontend`) contains common code in `apps/common/frontend`. You can add reusable utility functions, React components, or shared config here. Ensure anything added is exposed via the library's entrypoint, `index.ts`.

## Conversions between Java & Scala types

- Use Scala types wherever possible.
- Delay the conversion to Java types until the last possible point.
- Convert from Java to Scala as early as possible.
- To convert, import `scala.jdk.CollectionConverters.*` and use the `asScala` and `asJava` methods.
