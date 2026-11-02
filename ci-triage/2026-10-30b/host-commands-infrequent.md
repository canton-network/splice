# Host commands: close "Infrequent / no repro" issues with no re-occurrence in 3 weeks (2026-10-30)

A re-occurrence shows up on the umbrella issue as a cross-reference (a new issue closed as "Duplicate of #N") or a
comment. The script takes the newest cross-reference or comment, or the creation date if there is none, and treats
the issue as quiet when that is older than 21 days.

Run it once as a dry run (prints the list, changes nothing); then run it with `CLOSE=1` to close the quiet ones.
It only touches open cn-test-failures issues whose board status contains "infrequent" (case-insensitive).

```zsh
P=48; O=DACH-NY; R=DACH-NY/cn-test-failures
CUTOFF=$(date -u -v-21d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '21 days ago' +%Y-%m-%dT%H:%M:%SZ)
gh project item-list $P --owner $O --limit 2000 --format json > /tmp/p48-items.json
jq -r '.items[] | select((.status // "") | ascii_downcase | test("infrequent")) | select((.content.repository // "") | test("cn-test-failures")) | .content.number' /tmp/p48-items.json | sort -n > /tmp/p48-infrequent.txt
echo "status: $(jq -r '[.items[].status // empty] | unique | map(select(ascii_downcase | test("infrequent"))) | join(", ")' /tmp/p48-items.json), items: $(wc -l < /tmp/p48-infrequent.txt), cutoff: ${CUTOFF}"
while read -r n; do
  [ "$(gh issue view $n --repo $R --json state -q .state)" = OPEN ] || continue
  last=$(gh api "repos/$R/issues/$n/timeline" --paginate --jq '.[] | select(.event == "cross-referenced" or .event == "commented") | .created_at' | sort | tail -1)
  [ -z "${last}" ] && last=$(gh issue view $n --repo $R --json createdAt -q .createdAt)
  title=$(gh issue view $n --repo $R --json title -q .title)
  if [[ "${last}" < "${CUTOFF}" ]]; then
    echo "QUIET  $n  last ${last}  ${title}"
    [ "${CLOSE:-0}" = 1 ] && gh issue close $n --repo $R --reason "not planned" --comment "No re-occurrence since ${last%%T*} (3 weeks without a new hit); closing. Reopen if it comes back."
  else
    echo "ACTIVE $n  last ${last}  ${title}"
  fi
done < /tmp/p48-infrequent.txt
```
