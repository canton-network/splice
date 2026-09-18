# 10172 - multi-arch image check: skopeo returned a non-index manifest for a digest that is a 12-entry OCI index (run 35341655357)

Same weak spot as 10150 (one Docker Hub inspect failing the whole `deployment_test` job), second failure mode: no
skopeo error this time, but a response without a `manifests` list for a digest that Docker Hub serves as a
multi-arch index.

- Run: https://github.com/canton-network/splice/actions/runs/35341655357, release-line-0.8.x 495349f01f ("0.8.x:
  clear release notes and fix version after 0.8.3 cut (#7381)"), job 105588693020 `ci / deployment_test /
  deployment_test`, step "Check that pinned docker images are multi-arch" (`scripts/check-multiarch-images.py`,
  skopeo 1.23.0 from the nix shell). Canton pin 3.5.18 (irrelevant here). Only failed job of the run.

## 1. The failing image

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10172/job.log | sed -n 36952,37228p | sed -E 's/^[^Z]*Z //' | grep -v -E '^\s*Inspecting |^\s*OK: |^\s*$'
```
```
ERROR: ubuntu:24.04@sha256:440dcf6a5640b2ae5c77724e68787a906afb8ddee98bf86db94eea8528c2c076 (from cluster/images/splice-debug/Dockerfile) is not pinned to a multi-arch digest
FAIL: 1 image(s) are not pinned to multi-arch digests
```
The other 16 references, including six other Docker Hub images (python, debian, k6, node, postgres, nginx),
passed in the same run. No `unable to inspect` or `invalid manifest JSON` stderr line exists anywhere in the
job log (10150 had the 502 in that line), so `_inspect` returned False on its third branch: skopeo succeeded and
the parsed JSON had no non-empty `manifests` list. That branch prints nothing about what it received.

## 2. The digest is a multi-arch index and has not changed since 2025

```
git log --format='%h %ad %s' --date=short -S'440dcf6a5640b2ae5c77724e68787a906afb8ddee98bf86db94eea8528c2c076' -- cluster/images/splice-debug/Dockerfile | tail -1
TOK=$(curl -s "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/ubuntu:pull" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -H "Authorization: Bearer $TOK" -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json' https://registry-1.docker.io/v2/library/ubuntu/manifests/sha256:440dcf6a5640b2ae5c77724e68787a906afb8ddee98bf86db94eea8528c2c076 | python3 -c 'import sys,json;m=json.load(sys.stdin);print(len(m["manifests"]), [x["platform"]["architecture"] for x in m["manifests"]][:8])'
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -H "Authorization: Bearer $TOK" -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' https://registry-1.docker.io/v2/library/ubuntu/manifests/sha256:440dcf6a5640b2ae5c77724e68787a906afb8ddee98bf86db94eea8528c2c076
```
```
4aa2cea620 2025-07-21 pin all base images by digest (#1567)
12 ['amd64', 'unknown', 'arm', 'unknown', 'arm64', 'unknown', 'ppc64le', 'unknown']
200 application/vnd.oci.image.index.v1+json
```
Docker Hub returns the index even when asked for a single manifest type, so content negotiation does not
explain a non-index answer; the runner has no registry mirror configured (`.github/actions/nix`,
`build.deployment_test.yml`, job log: no `registries.conf`/mirror). What skopeo actually received is not
recoverable from this run.

## 3. Why one bad answer fails the job

`_inspect` (`scripts/check-multiarch-images.py:94-122` at 495349f01f) makes exactly one skopeo call per image and
maps every non-index outcome to "not pinned to a multi-arch digest"; the step runs with `cmd_retry_count: 0`.

## 4. Verdict and fix

Infra flake in the external registry path, second variant of 10150 (the digest and the Dockerfile are correct and
unchanged). Fix branch `ray/fix-multiarch-check-retry` (4dc7d6c36d, off main): the single-call body moves unchanged into `_inspect_once` and `_inspect` retries it up
to 3 times with a 5 s pause on registry errors, invalid JSON, or a response without a `manifests` list, and prints
the received `mediaType`, `schemaVersion` and top-level keys for the non-index case so the next occurrence
carries evidence. A genuinely single-arch pin still fails after three consistent answers. `py_compile` clean;
not run here (needs skopeo and registry access from the runner). Backport to release-line-0.8.x with the same
cherry-pick once merged.
