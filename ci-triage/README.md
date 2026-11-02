# CI failure triage

Shared triage branch, never merged. Procedure: the `ci-triage` skill on main (`.claude/skills/ci-triage/`).

- `HANDOVER.md`: current state, open inbox items and what each one needs next.
- `known-families.md`: flake family catalogue with confirming greps. Check it before any deep analysis.
- `<ref>-<slug>.md`: evidence packets from 2026-11-02 on (and Raymond's from 2026-09).
- `2026-10-27b/` ... `2026-11-02/`: Raymond's inbox rounds (packets, host commands, already run).
- `history/`: Raymond's index, handover and onboarding as of 2026-11-02, unchanged. The index
  (`history/README-2026-09-08_2026-11-02.md`) maps every ref triaged before 2026-11-02 to its run, job and verdict;
  grep it by ref.

Issue exports from the private tracker are not committed; they live in the host's `log/ci-triage-export/`.

## Ref -> run -> job mapping

| ref | run | branch sha (PR) | job id and name | canton pin |
|---|---|---|---|---|

## Overview

| ref | failure (one line, with the key timestamps) | duplicate of | packet + resolution/status |
|---|---|---|---|

## Fix branches

| branch | commit | fixes | verified here |
|---|---|---|---|

## Cross-cutting notes
