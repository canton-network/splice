# Host commands: move recurring issues out of "Infrequent / no repro" to Triaged (2026-10-30)

A hit is a cn-test-failures issue cross-referencing the umbrella (a new issue closed as "Duplicate of #N"). Issues
with 3 or more hits in the last 21 days are not infrequent. Dry run first (prints the counts); then `MOVE=1` sets
the board status of those to Triaged. Only open issues whose status contains "infrequent" are considered.

```zsh
P=48; O=DACH-NY; R=DACH-NY/cn-test-failures; MIN_HITS=3
CUTOFF=$(date -u -v-21d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '21 days ago' +%Y-%m-%dT%H:%M:%SZ)
PID=$(gh project view $P --owner $O --format json -q .id)
FID=$(gh project field-list $P --owner $O --format json -q '.fields[] | select(.name=="Status") | .id')
OID=$(gh project field-list $P --owner $O --format json -q '.fields[] | select(.name=="Status") | .options[] | select(.name=="Triaged") | .id')
gh project item-list $P --owner $O --limit 2000 --format json > /tmp/p48-items.json
jq -r '.items[] | select((.status // "") | ascii_downcase | test("infrequent")) | select((.content.repository // "") | test("cn-test-failures")) | "\(.content.number) \(.id)"' /tmp/p48-items.json | sort -n > /tmp/p48-infrequent-ids.txt
while read -r n iid; do
  [ "$(gh issue view $n --repo $R --json state -q .state)" = OPEN ] || continue
  hits=$(gh api "repos/$R/issues/$n/timeline" --paginate --jq ".[] | select(.event == \"cross-referenced\" and .created_at > \"${CUTOFF}\" and ((.source.issue.repository_url // \"\") | test(\"cn-test-failures\"))) | .source.issue.number" | sort -u | wc -l | tr -d ' ')
  owner=$(gh issue view $n --repo $R --json assignees -q '[.assignees[].login] | join(",")')
  title=$(gh issue view $n --repo $R --json title -q .title)
  if [ "${hits}" -ge ${MIN_HITS} ]; then
    echo "RECURRING ${hits} hits  $n  owner=${owner:-none}  ${title}"
    [ "${MOVE:-0}" = 1 ] && [ -n "${OID}" ] && gh project item-edit --id "${iid}" --project-id "${PID}" --field-id "${FID}" --single-select-option-id "${OID}" > /dev/null && echo "  -> Triaged"
  else
    echo "infrequent ${hits} hits  $n  ${title}"
  fi
done < /tmp/p48-infrequent-ids.txt
```
