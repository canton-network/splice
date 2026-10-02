#!/usr/bin/env bash
# Report the state of every cn-test-failures ref recorded on the ci-triage branch.
# Run from the splice repo root on a host whose `gh` login can read DACH-NY/cn-test-failures:
#   log/cn-test-failures-status.sh [extra refs...]
# Needs gh and python3. Writes log/cn-test-failures-status.tsv
# (ref, state, state reason, closed at, updated at, labels, title) and prints the open refs.
# If a ci-triage README.md sits next to this script, refs added there since are included.
set -euo pipefail

REPO_OWNER=${REPO_OWNER:-DACH-NY}
REPO_NAME=${REPO_NAME:-cn-test-failures}
OUT=${OUT:-log/cn-test-failures-status.tsv}
HERE=$(cd "$(dirname "$0")" && pwd)

REFS="8784 9136 9704 9740 9929 9965 10010 10048 10060 10084 10088 10091 10094 10111 10120 10121 10129 10133 10135
10136 10137 10139 10140 10141 10142 10143 10144 10145 10146 10147 10149 10150 10153 10154 10155 10156 10157 10158
10161 10162 10164 10165 10166 10167 10169 10170 10171 10172 10173 10174 10175 10176 10178 10179 10180 10182 10183
10184 10185 10186 10187 10188 10189 10190 10191 10192 10193 10195 10197 10204 10212 10214 10225 10227 10233 10234
10235 10236 10237 10238 10241 10242 10247 10248 10249 10256 10257 10264 10265 10266 10267 10268 10269 10270 10271
10272 10273 10312"

export REPO_OWNER REPO_NAME OUT HERE REFS
python3 - "$@" <<'EOF'
import json, os, re, subprocess, sys

owner, name, out, here = (os.environ[k] for k in ("REPO_OWNER", "REPO_NAME", "OUT", "HERE"))
refs = set(int(x) for x in os.environ["REFS"].split())
refs |= set(int(x) for x in sys.argv[1:] if x.isdigit())
readme = os.path.join(here, "README.md")
if os.path.exists(readme):
    text = [l.split("|")[1] for l in open(readme) if re.match(r"^\| *[0-9]", l)]
    text += [f for f in os.listdir(here) if f[:1].isdigit()]
    for t in text:
        refs |= {int(m) for m in re.findall(r"(?<![0-9])(?:8[0-9]{3}|9[0-9]{3}|10[0-9]{3})(?![0-9])", t.split("-")[0] if t.endswith(".md") else t)}
refs = sorted(refs)
print(f"checking {len(refs)} refs in {owner}/{name}", file=sys.stderr)

rows = []
for i in range(0, len(refs), 50):
    chunk = refs[i:i + 50]
    fields = " ".join(
        f"r{n}: issue(number: {n}) {{ number state stateReason closedAt updatedAt title labels(first: 10) {{ nodes {{ name }} }} }}"
        for n in chunk)
    query = f'query {{ repository(owner: "{owner}", name: "{name}") {{ {fields} }} }}'
    # Missing numbers produce GraphQL errors next to partial data: keep the data, report the misses.
    p = subprocess.run(["gh", "api", "graphql", "-f", f"query={query}"], capture_output=True, text=True)
    try:
        repo = json.loads(p.stdout)["data"]["repository"]
    except Exception:
        sys.exit(f"query failed:\n{p.stderr}\n{p.stdout[:2000]}")
    if repo is None:
        sys.exit(f"cannot read {owner}/{name} with this gh login:\n{p.stdout[:2000]}")
    for n in chunk:
        it = repo.get(f"r{n}")
        if it is None:
            rows.append([str(n), "NOT_FOUND", "", "", "", "", ""])
        else:
            rows.append([str(it["number"]), it["state"], it.get("stateReason") or "", it.get("closedAt") or "",
                         it["updatedAt"], ",".join(l["name"] for l in it["labels"]["nodes"]),
                         it["title"].replace("\t", " ")])

with open(out, "w") as f:
    f.write("ref\tstate\tstate_reason\tclosed_at\tupdated_at\tlabels\ttitle\n")
    for r in rows:
        f.write("\t".join(r) + "\n")
counts = {}
for r in rows:
    counts[r[1]] = counts.get(r[1], 0) + 1
print(f"wrote {out}: " + " ".join(f"{k}={v}" for k, v in sorted(counts.items())), file=sys.stderr)
print("open refs:", file=sys.stderr)
for r in rows:
    if r[1] == "OPEN":
        print(f"  {r[0]}  {r[6][:100]}", file=sys.stderr)
EOF
