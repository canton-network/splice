# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Splice - applications for operating Validators and Super-Validators (SVs) on the Canton Network. It
layers governance, validation, wallet, and token-standard functionality on top of a (modified) Canton
ledger. The repo is effectively a monorepo of several logically-independent components kept together
for head-based development (see `DEVELOPMENT.md` "Directory Layout").

Most authoritative docs live at the repo root: `DEVELOPMENT.md` (build/sbt/Daml), `TESTING.md`
(tests/CI), `TROUBLESHOOTING.md`, `CONTRIBUTING.md`, `AI_POLICY.md`. Read those for depth; this file
is the orientation map.

## Build / lint / format

The dev environment is provided via **Nix + direnv** - run `direnv allow` once per clone. sbt needs
the env vars from `.envrc` (notably `SBT_OPTS` with a large heap), so always run it through direnv.
Most developer operations go through sbt; enter `sbt` in the repo root for a shell, or run one-off
commands as `sbt '<command>'` (quote the whole command).

**Invoke all sbt commands through `sbt --client`.** This connects to a single persistent sbt server
instead of paying the cold JVM + settings load (~5 min) on every invocation; the first call boots the
server and subsequent calls return in seconds. So run `sbt --client '<command>'` (e.g.
`sbt --client compile`) rather than a bare `sbt <command>`. The examples below omit `--client` for
brevity, but it applies to all of them.

- `sbt compile` - production code; `sbt Test/compile` - production + test code.
- `sbt format` - apply scalafmt. `sbt formatFix` - scalafmt + scalafixAll + frontend fixes.
- `sbt lint` - check-only (scalafmt, buf, scalafix, frontend lint, shellcheck); applies no fixes.
  Run `formatFix` then `lint` before a PR.
- `sbt bundle` - build a release bundle in `apps/app/target/release/<version>` (the `amulet` binary).
- `sbt reload` - after switching branches or editing build files, to refresh sbt state.
- `sbt clean-splice` - clean only Splice build outputs (not the Canton fork); `sbt clean` cleans all.
  Use these when builds fail oddly after a branch switch before assuming a real failure.

**sbt project IDs use dashes, not slashes:** directory `apps/app` is project `apps-app`, `apps/common`
is `apps-common`, etc. Use `apps-app/testOnly ...`, never `apps/app/testOnly`.

## Tests

**Never run bare `sbt test`** (see the `test` note in `DEVELOPMENT.md` - it is not advisable; the full
suite runs in CI). Always scope with `testOnly`:

- Unit (no Canton): `sbt 'apps-<name>/testOnly fully.qualified.SpecName'`. Wildcards work:
  `testOnly *Wallet* -- -z "allow calling tap"` (`-z` filters by test description).
- Daml script tests: `sbt damlTest`.
- Frontend unit (vitest): from the frontend dir, `npm test [<file>]`; or `sbt apps-frontends/npmTest`.
- Helm: `make cluster/helm/test`. Pulumi: `make cluster/pulumi/unit-test`.

**Integration tests** live in `apps/app/src/test/scala/.../integration/tests/` and spin up a full
network topology. They require a long-running Canton started first. Never run bare `./start-canton.sh`
- always pass a mode: `./start-canton.sh -w` (wallclock, for most tests) or `./start-canton.sh -s`
(simtime). To switch modes, stop the running one first (`./stop-canton.sh`). Run them inside `apps-app` for
speed: `sbt 'apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.AnsIntegrationTest'`.
Tests whose name ends in `FrontendIntegrationTest` drive a browser (Selenium/Firefox) and also need
`./start-frontends.sh`. Logs go to `log/canton_network_test.clog` - inspect with `lnav`.

CI treats unexpected WARN/ERROR log lines as failures; expected ones must be wrapped with Canton's
`SuppressingLogger`. `sbt checkErrors` mirrors the CI log check locally.

After adding a test class, run `sbt updateTestConfigForParallelRuns` and commit the changed
`test-*.log` files (CI's static checks enforce this).

## Daml workflow

Daml smart contracts live under `daml/` (and per-app `daml/` subfolders); they define the on-ledger
APIs that synchronize parties. `.dar` archives are checked into `daml/dars/`.

- `sbt damlBuild` builds all `.dar`s; `sbt damlTest` runs Daml script tests.
- After Daml changes: `sbt updateDarResources` (CI enforces this before integration tests), and
  `sbt damlDarsLockFileUpdate` to update `dars.lock` (commit it alongside the dar changes).
- Daml changes **must be backwards-compatible** (packages are upgraded, not replaced). When adding
  enum constructors, only add nullary constructors to types that are already all-nullary.
- Bumping versions: `git fetch origin && sbt damlBumpPackageVersions` (bumps the changed package and
  everything depending on it). See `DEVELOPMENT.md` "Daml Version Bump" for the manual path.
- Editing: `sbt damlBuild`, then `dpm studio` to open VS Code with the Daml language server.
- **Daml interfaces** cannot be upgraded - once released they are frozen and excluded from
  compilation (prebuilt dar). See `DEVELOPMENT.md` "Maintaining Daml Interfaces".

## Architecture

The stack, bottom to top:

1. **Canton** (`canton/`) - a modified fork of Canton OSS providing the distributed ledger
   (participants, synchronizers, ledger API). Local modifications are documented in
   `CANTON_CODE_CHANGES.md`. sbt projects are `canton-community-*`.
2. **Daml** (`daml/`) - on-ledger domain models: `splice-amulet` (the Amulet token), `splice-wallet`
   / `splice-wallet-payments`, `splice-dso-governance` (DSO = Decentralized Synchronizer Operator),
   `splice-validator-lifecycle`, `splice-amulet-name-service` (ANS), `splitwell`, plus the
   `splice-api-token-*` interface packages implementing the token standard (CIP-0056, see
   `token-standard/`).
3. **Scala backends** (`apps/`) - one subproject per node/service, each binding the Daml codegen and
   exposing OpenAPI HTTP APIs:
   - `apps-validator` - Validator node.
   - `apps-sv` - Super-Validator node (DSO governance, validator lifecycle automation).
   - `apps-scan` - read/indexing service over ledger data (also has protobuf/gRPC).
   - `apps-wallet` - wallet/payments backend.
   - `apps-splitwell` - example expense-splitting app.
   - `apps-common` (+ `apps-common-sv`) - shared libraries, HTTP/OpenAPI plumbing, DAR resource
     bundling.
   - `apps-app` - **the entry point** (`SpliceApp`, `apps/app/src/main/scala/.../SpliceApp.scala`):
     aggregates all backends + Canton into the deployable `splice-node` bundle, and hosts the
     integration-test suite.
   Backends run **automation triggers** that react to on-ledger state changes (search for `*Trigger`).
4. **Frontends** - React/TypeScript, located under each app's `frontend/` dir (`apps/*/frontend/`),
   managed as npm workspaces declared in `apps/package.json`. Shared code lives in
   `apps/common/frontend` (`@canton-network/splice-common-frontend`), exposed via its `index.ts`.

### Code generation (don't hand-edit generated output)

- **OpenAPI specs** in `apps/*/src/main/openapi/*.yaml` are the source of truth for HTTP APIs.
  Guardrail generates Scala server/client code; the OpenAPI generator produces TypeScript clients for
  the frontends.
- **Daml codegen** produces TypeScript bindings (`daml.js/`) consumed by frontends, and Java codegen
  for some packages.
- After changing OpenAPI or Daml, recompile so generated code (and npm lock files) regenerate.

## Deployment (cluster/)

`cluster/` holds the Pulumi (IaC) and Helm definitions for deploying networks. Changes to deployed
state require updating checked-in expectations: `make cluster/pulumi/update-expected` and the resolved
config (`make cluster/deployment/update-resolved-config -j`); pre-commit and CI enforce they stay in
sync. See `TESTING.md` "Deployment Tests".

## Conventions (from .github/copilot-instructions.md)

- **Protobuf:** `proto3` only; `.proto` files under `src/main/protobuf`, one service per file; refer
  to generated classes with a version package prefix (`v0.MyMessage`). Use `repeated` plural names;
  `*_contract_id` / `*_party_id` string fields for ids.
- **Daml Numerics:** `scala.math.BigDecimal` for user-facing/console APIs; `string` in Protobuf
  (convert via `org.lfdecentralizedtrust.splice.util.Proto.encode/tryDecode`); Java `BigDecimal` at
  the Ledger API. `wallet.tap` is the canonical example.
- **Java/Scala:** prefer Scala types; convert from Java as early and to Java as late as possible
  (`scala.jdk.CollectionConverters.*`, `asScala`/`asJava`).
- **Config flags:** name as `enableXXX`, never `disableXXX` (avoid double negation). Configure apps
  via HOCON; only environment-variable overrides are fully supported (not system props).
- **Domain naming:** `listXXX`/`acceptXXX`/`rejectXXX`/`withdrawXXX` for proposals/requests; use
  `amount` (not `quantity`/`number`) and `sender`/`receiver` (not `payer`/`payee`).
- **DB migrations:** see `apps/common/src/main/resources/db/migration/README.md`.

## Commits & CI

Commit messages need a CI tag: `[static]` (lint/format/deployment-only - skips integration tests),
`[ci]` (full CI incl. integration), `[skip ci]` (no merge), `[force]` (skip tests but allow merge),
`[breaking]` (excludes the change from the app-upgrade compatibility test). PRs from branches in this
repo are **not** CI'd unless opted in with `[ci]`/`[static]`. Sign off commits (DCO).
