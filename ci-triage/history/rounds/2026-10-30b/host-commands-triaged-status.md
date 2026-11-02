# Host commands: project 48 Status Inbox -> Triaged for open issues with a certain root cause (2026-10-30)

Moves only items whose Status is still Inbox; running it twice is harmless. Stops if the project has no "Triaged"
status option.

Triaged (root cause certain): 10729 10732 10740 10742 10912 10915 10921 10927 10930 10933 10934.
Left in Inbox (cause not established): 10728 (codegen javac, mechanism only), 10893 (first hit), 10917 (recheck after
#8130), 10918 (root cause not found), 10919 (lock holder unknown), 10937 (Canton or splice not yet known).

```zsh
P=48; O=DACH-NY
PID=$(gh project view $P --owner $O --format json -q .id)
FID=$(gh project field-list $P --owner $O --format json -q '.fields[] | select(.name=="Status") | .id')
OID=$(gh project field-list $P --owner $O --format json -q '.fields[] | select(.name=="Status") | .options[] | select(.name=="Triaged") | .id')
[ -n "${PID}" ] && [ -n "${FID}" ] && [ -n "${OID}" ] || { echo "project, Status field or Triaged option not found"; return 1 2>/dev/null || exit 1; }
gh project item-list $P --owner $O --limit 2000 --format json > /tmp/p48-items.json
triaged() {
  local iid st
  iid=$(jq -r --argjson n $1 '.items[] | select(.content.number == $n and (.content.repository // "" | test("cn-test-failures"))) | .id' /tmp/p48-items.json | head -1)
  st=$(jq -r --argjson n $1 '.items[] | select(.content.number == $n and (.content.repository // "" | test("cn-test-failures"))) | .status' /tmp/p48-items.json | head -1)
  if [ -z "${iid}" ]; then echo "$1: not on the board"; return; fi
  if [ "${st}" != "Inbox" ]; then echo "$1: status ${st}, left as is"; return; fi
  gh project item-edit --id "${iid}" --project-id "${PID}" --field-id "${FID}" --single-select-option-id "${OID}" > /dev/null && echo "$1: Triaged"
}
triaged 10729; triaged 10732; triaged 10740; triaged 10742; triaged 10912; triaged 10915
triaged 10921; triaged 10927; triaged 10930; triaged 10933; triaged 10934
```
