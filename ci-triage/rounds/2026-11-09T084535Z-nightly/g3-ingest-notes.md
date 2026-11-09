# g3-ingest: 11280

Verdict: new, deterministic CI config break from canton-network/splice#8269 (VerdictStore perf test added to the
scheduled job without a threshold rule; the check runs only on schedule). Packet
`evidence/11280-verdict-perf-no-threshold.md`, comment `comments/11280.md`.
Suggested: comment, retitle, Triaged, assignee jagathweerasinghe-da (author of #8269 and of the thresholds file).
Lesson: none (CI config, not test code).
