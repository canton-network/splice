# Scan party monitor

`scan_party_monitor.py` watches a Scan instance for activity of a configured set of
**tracked parties** and reports:

- **transfers** of Amulet from a tracked party to a party that is not one of its expected
  recipients, and
- for tracked parties with `alert_confirmations` set, every transaction in which the party
  is a **confirming party**, according to the mediator verdict. This includes
  transactions that are private to other parties than the DSO party, and rejected ones.

Matches are printed to stdout as one JSON object per transaction, and optionally posted to
a Slack channel through an incoming webhook.

The script only needs Python 3 and its standard library.

## Usage

```bash
export SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...   # optional
./scan_party_monitor.py \
  --scan-url https://scan.sv-1.<network-host> \
  --db ./party-monitor.sqlite \
  --config ./parties.json
```

Run with `-h` for all options. Generally, it can be used either as a long-running
terminal application doing its own `--poll-interval-seconds` (default 10), or as
a cron job with either `--stop-at-record-time` or `--exit-when-caught-up`.

Use a Scan instance of an SV you work with; for best results, avoid the most
heavily-loaded SVs.

### Configuration

```json
{
  "expected_recipients": ["exchange::1220...", "custodian::1220..."],
  "parties": {
    "alice::1220...": {"additional_recipients": ["bob::1220..."]},
    "carol::1220...": {"alert_confirmations": true},
    "dave::1220...": {}
  }
}
```

The best usage pattern is to start out with your parties and recipients of concern
as `parties` and `expected_recipients`, then use `alert_confirmations` for parties
where much more verbosity is desired.

- `parties` lists the tracked parties (at most 1000).
- `expected_recipients` (optional) are parties that every tracked party may transfer to
  without an alert.
- `additional_recipients` (optional) are further expected recipients of one party; they are
  added to `expected_recipients`, not a replacement for them. Transfers from a party to
  itself (e.g. merging its holdings) never alert.
- `alert_confirmations` (optional, default `false`) additionally reports every transaction
  the party confirms, whatever it does.

To change the configuration, edit the file and restart the script with the same `--db`.
Changes to recipients and `alert_confirmations` apply from the next update on. Adding a
party requires restarting from ACS (see below).

### What is reported

Each match has a reason:

| reason       | applies to                         | meaning                                                                 |
|--------------|------------------------------------|-------------------------------------------------------------------------|
| `transfer`   | all tracked parties                | the transaction archives a holding of the party and creates one for `receiver`, who is not an expected recipient |
| `confirming` | parties with `alert_confirmations` | the party is a confirming party of the transaction views in `views`    |

Transfers directly, through a transfer preapproval, or through a token standard
transfer instruction are all detected.

A view's confirming parties are the signatories and acting parties of its action and, for
the root views, the admin party of the submitting participant. So a participant's admin
party confirms every transaction submitted through that participant; tracking it with
`alert_confirmations` reports all of them.

### Output

One JSON line per matching transaction, e.g.:

```json
{"update_id": "1220...", "migration_id": 4, "record_time": "2026-10-26T10:00:00.123456Z",
 "scan_url": "https://scan.sv-1...", "verdict_result": "VERDICT_RESULT_ACCEPTED",
 "parties": {"alice::1220...": [
   {"reason": "transfer", "event_id": "1220...:3", "template_id": "...:Splice.Amulet:Amulet",
    "receiver": "mallory::1220...", "amount": "1000.0000000000"}]}}
```

`verdict_result` is `VERDICT_RESULT_REJECTED` for a rejected transaction. `"private": true`
marks an accepted transaction that the DSO party does not see at all, so that Scan only has
the mediator verdict on it.

Look up the transaction and its verdict with `GET <scan-url>/api/scan/v0/events/<update_id>`.

Progress, warnings and a periodic status line go to stderr.

### Slack

Create an [incoming webhook](https://api.slack.com/messaging/webhooks) for the channel that
should receive alerts, and pass its URL in `SLACK_WEBHOOK_URL` (preferred, as the URL is a
secret) or `--slack-webhook-url`.

A message lists a limited number of transactions; stdout always has all of them.

## Implementation details

The script follows Scan's event stream (`/v0/events`), which pairs each transaction, as far
as the DSO party sees it, with the mediator verdict on it. The verdict names the confirming
parties of each view. A transfer archives holdings of the sender, but the stream doesn't
say whose holdings were archived, so the script keeps the tracked parties' Amulet and
LockedAmulet holdings in the SQLite file given by `--db`:

1. **Seed.** On first start, the script downloads the tracked parties' holdings from the
   most recent ACS snapshot of the latest migration (`/v2/holdings/state`). Every page is
   saved before the next is requested, so you can stop the script at any time; the next start
   resumes from the last saved page.
2. **Stream.** It then follows the event stream from the snapshot's record time onwards,
   reporting matches and keeping the holdings up to date. Its position is saved together with
   the holdings, so a restart resumes where it stopped.

Note that the script reports matches in all events after the snapshot, which can be a few
hours before the script was first started.

**Replaying a fixed range.** `--seed-snapshot-before TIME` seeds from the latest snapshot at
or before `TIME` instead of the latest one, and `--stop-at-record-time TIME` exits once all
events up to `TIME` have been processed. Together, they make a run reproducible, e.g. to
check a configuration against past transactions.

**Adding parties.** The stored holdings only cover the parties they were seeded for. When the
configuration adds parties, the script seeds the holdings again from the latest snapshot
before its current position and replays the events from there.

**Delivery.** Alerts are delivered at least once: the position is only saved after the
alerts up to it have been printed and posted to Slack. If the Slack webhook is unreachable
the script keeps retrying rather than skipping alerts. After a crash, alerts since the
last saved position may be repeated.

## Rate limits

Scan rate-limits each endpoint **across all of its clients** (for `/v0/events` the default
is 25 requests per second for everyone together). The script therefore paces its own
requests: by default 1 ACS request per second while seeding and 2 event requests per second
while catching up, then one poll every 10 seconds. As the seed only fetches the tracked
parties' holdings, it usually takes very few requests, though as a result it is
better to be conservative with your `parties` setting; e.g. avoid setting an SV
or the DSO party unless you really need alerts on them.

Please do not raise `--acs-requests-per-second` or `--events-requests-per-second`: doing so
slows down Scan for everyone else, including other users of this script.
(`--updates-page-size` and `--updates-requests-per-second` are deprecated names of the
`--events-*` options.)

## Limitations

- Transfers are detected from the parts of transactions visible to the DSO party, which
  include all Amulet holdings. Of a transaction that the DSO party does not see at all, Scan
  only has the mediator verdict, so it is only reported for parties with
  `alert_confirmations`.
- A transfer funded only from reward coupons, without consuming any of the sender's
  holdings, is not detected as a transfer; it is reported for parties with
  `alert_confirmations`.
- Burning holdings without creating new ones (e.g. to buy traffic) is not a transfer and
  is only reported for parties with `alert_confirmations`.
- If the snapshot to seed from disappears from Scan before the seed completes (Scan may prune
  old snapshots), the script stops with an error; delete the database file and start over.

## Tests

`test_scan_party_monitor.py` has unit tests against an in-process fake of Scan. They only need
Python 3 and take a few seconds. Run them from anywhere in the repository with

```bash
scripts/run-python-tests.sh scripts/scan-party-monitor
```

(paths are relative to the current directory; without an argument the script runs the tests
of all Python code in the repository). The script is also type checked in CI, which you can do
locally with `scripts/check-python-types.sh scripts/scan-party-monitor`.

`ScanPartyMonitorScriptIntegrationTest` runs the script against a more realistic local Scan
instance like other Scala integration tests.
