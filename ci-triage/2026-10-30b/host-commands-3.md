# Host commands, 2026-10-30b part 3: 10846, 10843, 10745 from the fetched logs

State-guarded: running it twice is harmless. Evidence: `needs-log-10745-10843-10846.md`.

```zsh
R=DACH-NY/cn-test-failures
is_open() { [ "$(gh issue view $1 --repo $R --json state -q .state)" = OPEN ]; }
is_open 10846 && gh issue close 10846 --repo $R --reason "not planned" --comment "Duplicate of #10844: sv-1 and sv-da-1 sv-app/scan and both validators never became available (Helm timeout) on ciperiodic at 10-29 04:45, inside the PV 36 founding window (cni #7746 to splice #8125)."
is_open 10843 && gh issue close 10843 --repo $R --reason "not planned" --comment "CILR SV was about 31.8 h behind (BFT ordering delay 114429 s) and catching up at about 7x real time; it reached 12620 s when the 240 min window ended, so not stuck, the backlog was too large for the window. Monitor note: the first readings are a 999 s value, so the 'between 60 s and 14400 s' branch was chosen before the real delay showed up."
is_open 10745 && gh issue close 10745 --repo $R --reason "not planned" --comment "Duplicate of #10915: sv-da-1's Cloud SQL backup of the cn-apps db ran longer than the script waits ('Timed out waiting for backup of cn-apps db' 17:33), retries then hit HTTP 409 'another operation in progress' until the job ended at 21:20. After cni #7727, so not #10643; the same slow 16:00 backup is what the 18:00 status check in #10915 sees."
```
