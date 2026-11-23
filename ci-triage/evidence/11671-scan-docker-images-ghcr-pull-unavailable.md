# 11671 - scan_docker_images: one un-retried docker pull answered as unavailable by the registry

New, not a duplicate. Scheduled Bump CILR run (splice dbb2a2c9de, snapshot
0.10.3-snapshot.20261119.4432.0.vdbb2a2c9). The step Scan docker images runs splice's `make docker-scan` with four
parallel jobs and no retry of the step. Each image is scanned twice by `build-tools/docker-scan` (full image, then
the layers above the base image), and each scan starts with a plain `docker pull`. Of 50 pulls one failed: the
second pull of `splice-test-ci` at 03:00:43 UTC on 2026-11-20, nine seconds after its first Black Duck scan had
succeeded and four minutes after the same tag had been pulled without a problem (02:56:21-02:56:45). ghcr.io
answered that pull as unavailable once; pulls of other images 2.5 min before and 1 min after succeeded. The Black
Duck scan itself is retried 10 times in the script, the pull is not, so one registry blip fails the job: make
stops on the `splice-test-ci` target (`cluster/images/local.mk:135`) and the job ends at 03:12 when the other three
parallel scans have finished. All 49 scans that ran succeeded.

- Splice: dbb2a2c9de.
- Runtime canton: not involved (image scan only).
- Component: infra (ghcr.io transient) hitting a splice build script without a retry (`build-tools/docker-scan`).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. No GitHub incident at the time

One refusal in 50 pulls, the neighbouring pulls (02:58:12, 03:01:45, 03:02:55) fine, and no incident listed:

```
python3 .claude/skills/ci-triage/scripts/github_incidents.py 2026-11-20T03:00:43Z
```
```
2026-11-20 03:00:43 UTC: 0 incident(s)
```

## 2. The script: scan retried, pull not

`build-tools/docker-scan` is identical at the run's splice sha and on origin/main
(run in the splice checkout: `git diff --stat dbb2a2c9de origin/main -- build-tools/docker-scan cluster/images/local.mk` prints nothing).
`do_scan` is called twice per image and each call pulls; the Black Duck call sits in `for i in {1..10}`.

```
git show dbb2a2c9de:build-tools/docker-scan | grep -n -E 'docker pull|for i in|^do_scan|blindly retry'
```
```
56:  docker pull --platform linux/amd64 "$image"
67:  # We blindly retry on failures, as we've seen unexplained failures in the scans that we couldn't resolve.
69:  for i in {1..10}; do
117:do_scan "$image" "$image_name" "$image_version"-full "$index"
121:do_scan "$image" "$image_name" "$image_version" "$index" "--detect.docker.platform.top.layer.id=$top_base_layer"
```

## 3. Duplicate check

- `known-families.md` has no scan_docker_images or Black Duck family. Family J lists 10133 (ghcr.io pull i/o
  timeout in a docker compose test, closed as completed): other job, other error text, no confirming grep, so not
  claimed as a parent.
- Tracker: `gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "scan_docker_images" created:>=2026-09-01'`
  returns only 11671. `"error from registry"` returns 8911, 5764, 5729 (all closed, docker-compose tests).
- Workflow history: 11 of the 15 runs before this one succeeded. The job also failed in a manually dispatched run
  of 2026-11-17 13:30; its log is no longer available, so its cause is not known and no issue was filed for it.

## 4. Side finding, not the failure

Five images whose Dockerfile base is `<image>@${image_sha256}` (canton, canton-participant, canton-sequencer,
canton-mediator, splice-test-docker-runner) each print an invalid-reference-format error twice between their two
scans. That is `get_top_base_layer`: the build arg is not passed to the Dockerfile parser, `docker buildx imagetools
inspect` gets a malformed reference, and the second scan runs with an empty `--detect.docker.platform.top.layer.id=`.
The scans still report success, so for these images the "layers above the base" scan is probably a second full
scan. Not verified beyond the job output; not touched by the fix.

## Verdict

New one-off, infra flake (ghcr.io answered one pull as unavailable) made fatal by a missing retry in splice.
Fix location: splice `build-tools/docker-scan`. Fix branch `pperek/fix-11671-docker-scan-pull-retry` (off
origin/main 7b71d35779), commit 8170f64f1d: the pull moves into `pull_image`, 5 attempts 10 s apart, same shape as
the scan retry below it.

Verified: `bash -n`; the pre-commit hooks including shellcheck pass; `pull_image` extracted from the script and
run against a stub `docker` that prints the registry error: 0 failures 1 call exit 0, 2 failures 3 calls exit 0,
5 failures exit 1 after `docker pull failed after 5 attempts`. NOT verified: a real `make docker-scan` run (needs
the Black Duck token and the pushed images); no sbt project or test suite covers this script, so nothing was
compiled or run with sbt. Not known: why ghcr.io refused this one request, and whether an outage would be over
within the 40 s the retry covers.
