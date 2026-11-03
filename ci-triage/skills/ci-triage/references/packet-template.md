# Packet template

File: `ci-triage/evidence/<ref>-<short-slug>.md`. Title line first, then one paragraph of verdict, then numbered
sections. Every section is: one sentence of what the command shows, the command in a fence, the verbatim
output in a fence (long hashes trimmed by the sed/cut in the command itself, e.g. `s/1220[0-9a-f]{60}/../g`).

```
# <ref> - <one-line failure description> (run <run id>)

<DUPLICATE of ... | new> ... one paragraph: branch/sha, job, canton pin, tests passed/failed, what checkErrors flagged.

- Run: <url>, <branch> <sha> ("<commit title>"), job <id> `<job name>`.
- Runtime canton: <version from nix/canton-sources.json at the sha>.
- Component: <test | splice app | Canton module | infra>.

## 1. Flagged lines / failing assertion
## 2. Which test was running (suite timeline around the timestamps)
## 3..n. Mechanism, one section per hop, each with command + output
## Verdict
Duplicate of / family; flake or real; fix location; fix branch if any; what was NOT verified.
```

Index row in `ci-triage/evidence/INDEX.tsv`: one line per ref, sorted by ref, tab-separated, no tabs or newlines in
cells, empty cell for unknown (never guessed). Columns:
`ref date run branch job canton summary dup_of category status file`
- `date`: day of the last triage; `run`: GHA run id; `branch`: branch and sha (PR); `job`: job id and name;
  `canton`: pin from `nix/canton-sources.json`; `summary`: one line, with the key timestamps; `dup_of`: parent ref
  and family letter, or `new (<family>)`; `category`: free-form (early rows only); `status`: resolution,
  issue action, fix branch; `file`: packet path(s) relative to `ci-triage/`, space-separated.
- Lookup: `awk -F'\t' '$1 == <ref>' ci-triage/evidence/INDEX.tsv`; grep by test name or family across `summary`
  and `dup_of`. Several refs in one packet get one row each with the same `file`.
