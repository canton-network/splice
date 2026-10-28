# 10469 - fast-uri 3.1.8 in configs-private (hand-off)

Status 2026-10-28: someone else is taking this; notes for them. In canton-foundation/configs-private you push from a
fork (no write rights on the repo needed).

- Finding: BlackDuck on `cluster/configs/configs-private` (submodule of canton-network-internal ->
  canton-foundation/configs-private): `ajv-cli 5.0.0 > ajv 8.20.0 > fast-uri 3.1.8`. Fixed in 3.1.9 (2026-10-19,
  older than 7 days on 10-28) and 4.2.3; ajv 8.20.0 depends on `fast-uri ^3.0.1`, so a lockfile-only bump to 3.1.9
  is enough, `package.json` stays.
- Fix in configs-private (from a fork):

```zsh
gh repo fork canton-foundation/configs-private --clone=true --remote=true
cd configs-private
git fetch upstream
base=$(git remote show upstream | sed -n 's/.*HEAD branch: //p')
git switch -c bump-fast-uri "upstream/${base}"
npm update fast-uri --package-lock-only --ignore-scripts
git diff --stat
npm ls fast-uri --package-lock-only
git add package-lock.json
git commit -s -m "Bump fast-uri to 3.1.9"
git push -u origin bump-fast-uri
gh pr create -R canton-foundation/configs-private --base "${base}" --fill
```

  PR body: "Lockfile-only bump of fast-uri 3.1.8 -> 3.1.9 (ajv-cli > ajv 8.20.0, range ^3.0.1).
  Fixes https://github.com/DACH-NY/cn-test-failures/issues/10469"
- BlackDuck scans it through the cni submodule: 10469 clears once the cni submodule pointer includes the fix.
- Same version in splice main `cluster/pulumi/package-lock.json` (public): prepared as sandbox branch
  `ray/bump-fast-uri` a2ad1bef18 ("[static] Bump fast-uri to 3.1.9 in cluster/pulumi", lockfile-only, 3 lines),
  not pushed; equivalent: `cd cluster/pulumi && npm update fast-uri --package-lock-only --ignore-scripts`.
