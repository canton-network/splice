# 11285 - reset_validator_runbook_devnet (release-line-0.10.1): nix setup, GitHub release download 504 during a GitHub incident

ONE-OFF, external incident. CircleCI pipeline 88472, job 529977, release-line-0.10.1 f3911e2 ("Backport PR #8021 to
release-line-0.10.1 (#8022)"). The nix setup step missed the runner binary cache for the pulumi github provider
tarball, built it, and the fetch from GitHub releases got HTTP 504 on all 4 tries; nix shell setup failed ("Failed to
load nix packages after 5 attempts"). GitHub status had a minor incident "Increased timeouts due to surge in anonymous
users" from 2026-11-09 05:59 to 09:09 UTC; the issue was filed at 06:13:21Z, so the job failed between 05:59 (at the
earliest, given the incident) and 06:13.

- Job: https://app.circleci.com/pipelines/github/DACH-NY/canton-network-internal/88472/workflows/ebb8acad-30c9-43b6-8209-2bd1bc2b6254/jobs/529977/parallel-runs/0
- Component: infra (GitHub releases, CircleCI runner nix binary cache). Family J (cf. 10185-10195, nix flake input 504).

## 1. Failing step (pasted by the user from CircleCI, saved as log/11285/nix-setup-step.txt, progress lines dropped)

```
building '/nix/store/hiyg3fhnj43lgyq8g0i1m4r2jh8srm98-pulumi-resource-github-v6.13.1-linux-amd64.tar.gz.drv'...
       > trying https://github.com/pulumi/pulumi-github/releases/download/v6.13.1/pulumi-resource-github-v6.13.1-linux-amd64.tar.gz
       > curl: (22) The requested URL returned error: 504
       > error: cannot download pulumi-resource-github-v6.13.1-linux-amd64.tar.gz from any mirror
Failed to load nix packages after 5 attempts
```

No timestamps in the paste; the step's time is bounded only by the issue creation (06:13:21Z).

## 2. GitHub incident

```
python3 ci-triage/scripts/github_incidents.py --margin-min 30 2026-11-09T06:05:00Z
```
```
2026-11-09 06:05:00 UTC: 1 incident(s)
  2026-11-09 05:59 to 2026-11-09 09:09 UTC minor | Increased timeouts due to surge in anonymous users |  | https://stspg.io/jztczfplj6yb
```
The download is an unauthenticated GitHub release fetch (nix fetchurl), the class the incident names.

## 3. Duplicate check

known-families J: 10185-10195 (nix flake input `nix-systems/default` tarball, HTTP 504, 2026-09-21) is the same
class (GitHub download 504 during nix setup after a binary cache miss). No other issue for reset_validator_runbook_devnet
in the 2026-11-09 12:13 board export (inbox, in-progress, triaged). Sibling 10680 (same workflow, release-line-0.9.4,
2026-10-27) still has no log; not assumed to be the same.

Not verified: why the runner's binary cache lacked this tarball (release-line-0.10.1 pins pulumi 3.268.0 /
pulumi-github 6.13.1; possibly not used by main any more, so not warm in the cache).

## Verdict

One-off, external GitHub incident; close as not planned. Lesson: none.
