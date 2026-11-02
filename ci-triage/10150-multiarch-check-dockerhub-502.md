# 10150 - multi-arch image check fails on a Docker Hub 502 (run 35103689739)

Branch main, sha c1baff4cfc "Export topology metrics only on SV-1 (#7341)", post-merge CI 2026-09-16T13:42Z.
Failed job 104819283178 `ci / deployment_test / deployment_test`, step "Check that pinned docker images are
multi-arch". No test artifacts; the job log is the evidence. Commands verified.

Ref mapping: given by Raymond as (35103689739, 10150).

## Categorization

- Test failed: none. `scripts/check-multiarch-images.py` (run by `.github/workflows/build.deployment_test.yml:44`)
  exited 1.
- Failure type: infra, transient. Docker Hub returned HTTP 502 for one manifest read; the script counts any
  `skopeo inspect` failure as "not multi-arch".
- Component: CI check script / external registry. Not #7341 (cluster config and expected-json only).
- Flake vs real: once-off flake. The digest is a multi-arch index (verified below).

## Setup

```
gh api repos/canton-network/splice/actions/jobs/104819283178/logs > job.log
```

## 1. Failed step and the one failing image

```
gh api repos/canton-network/splice/actions/jobs/104819283178 --jq '[.steps[] | select(.conclusion=="failure") | .name] | join("|")'
grep -aE 'ERROR:|FAIL:' job.log | sed -E 's/^[0-9T:.Z-]+ //' | fold -w 200
```
```
Check that pinned docker images are multi-arch
ERROR: unable to inspect docker://python@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a: time="2026-09-16T13:49:18Z" level=fatal msg="Error parsing image name \"docker://pytho
n@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a\": reading manifest sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a in docker.io/library/python: recei
ved unexpected HTTP status: 502 Bad Gateway"
ERROR: python:3.12-slim@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a (from cluster/images/cometbft-watchdog/Dockerfile) is not pinned to a multi-arch digest
FAIL: 1 image(s) are not pinned to multi-arch digests
```
skopeo's "Error parsing image name" wording is misleading; the quoted cause is the registry response
`502 Bad Gateway` while reading the manifest from `docker.io/library/python`.

## 2. Every other image, including four other Docker Hub images, resolved in the same run

```
grep -acE '  OK: .* is multi-arch' job.log
grep -aE '  OK: (debian|ubuntu|postgres|node|grafana)' job.log | sed -E 's/^[0-9T:.Z-]+ +OK: //; s/@sha256:[0-9a-f]+//'
```
```
13
debian:bookworm-slim is multi-arch
grafana/k6:1.2.2 is multi-arch
node:22-alpine-3.21 is multi-arch
ubuntu:24.04 is multi-arch
postgres:14.18 is multi-arch
```

## 3. The digest is a multi-arch index

Checked from the sandbox with the same tool the script uses:

```
skopeo inspect --raw --no-creds docker://docker.io/library/python@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["mediaType"]); print(sorted(set(m["platform"]["os"]+"/"+m["platform"]["architecture"] for m in d["manifests"] if m["platform"]["architecture"]!="unknown")))'
```
```
application/vnd.oci.image.index.v1+json
['linux/386', 'linux/amd64', 'linux/arm', 'linux/arm64', 'linux/ppc64le', 'linux/riscv64', 'linux/s390x']
```

## 4. Nothing changed on either side

```
gh pr view 7341 --repo canton-network/splice --json files --jq '[.files[].path] | join(" ")'
gh api "repos/canton-network/splice/commits?path=cluster/images/cometbft-watchdog/Dockerfile&sha=c1baff4cfc&per_page=1" --jq '.[] | "\(.sha[:10]) \(.commit.author.date[:10]) \(.commit.message | split("\n")[0][:60])"'
gh api "repos/canton-network/splice/commits?path=scripts/check-multiarch-images.py&sha=c1baff4cfc&per_page=1" --jq '.[] | "\(.sha[:10]) \(.commit.author.date[:10]) \(.commit.message | split("\n")[0][:60])"'
```
```
cluster/configs/shared/base-sv.yaml cluster/configs/shared/scratchnet.yaml cluster/configs/shared/validator-topology-metrics-export.yaml cluster/deployment/mock/config.yaml cluster/deployment/scratchneta/config.resolved.yaml cluster/deployment/scratchnetb/config.resolved.yaml cluster/deployment/scratchnetc/config.resolved.yaml cluster/deployment/scratchnetd/config.resolved.yaml cluster/deployment/scratchnete/config.resolved.yaml cluster/expected/sv-runbook/expected.json cluster/expected/sv/expected.json
44199952e8 2026-08-18 Restart CometBFT when it starts replaying messages (#6823)
fff5ae3c1e 2026-09-03 feat: add checks for FROM directives (#7068)
```
The python pin dates from 2026-08-18 and the FROM-directive check from 2026-09-03; the check has passed on
every main run in between (deployment_test success on 34625151951, 34620257412, 34616725655 and the
2026-09-16 runs before this one).

## 5. Why a registry hiccup fails the build

```
awk 'NR>=98&&NR<=112' scripts/check-multiarch-images.py
```
```
    ref = f"docker://{image}@sha256:{digest}"
    try:
        raw = subprocess.run(
            ["skopeo", "inspect", "--raw", "--no-creds", ref],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except subprocess.CalledProcessError as e:
        print(f"ERROR: unable to inspect {ref}: {e.stderr.strip() or e}", file=sys.stderr)
        return False
```
`_inspect` returns False for any skopeo error, so a 502 is reported as "not pinned to a multi-arch digest".
The step runs through `run_bash_command_in_nix` without a retry wrapper, so the single skopeo call decides.

## Root cause / hypothesis

Proven: one `skopeo inspect` against Docker Hub received HTTP 502 at 13:49:18Z; the digest is a valid
multi-arch index; nothing relevant changed in the commit or in the checked files. Infra flake in the external
registry, amplified by the script treating inspect errors as check failures.

## Duplicates / related

- None among the current refs. Same class as 10133 (ghcr.io pull i/o timeout in docker-compose validator
  test): a transient registry error failing a CI job.

## Suggested next step / owner

1. Re-run the job.
2. Harden `scripts/check-multiarch-images.py` (owner: CI / whoever added #7068): pass `--retry-times 3` to
   skopeo, and distinguish "could not inspect" (fail with a clear infra message, or retry) from "inspected,
   single-arch". Optionally resolve short names explicitly (`docker.io/library/...`) so the skopeo error text
   is not the misleading "Error parsing image name".
