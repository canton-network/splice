# Scan party monitor

`scan_party_monitor.py` watches a Scan instance for activity of a configured set of
**tracked parties** and reports:

- **transfers** of Amulet from a tracked party to a party that is not one of its expected
  recipients, and
- for tracked parties with `alert_confirmations` set, every transaction in which the party
  is a **confirming party**.

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

Run with `-h` for all options.

Scan's public API is only reachable from IP addresses that the SV operating the Scan
instance has allowlisted. Use a Scan instance of an SV you work with.

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
party makes the script re-seed its contracts (see below).

### What is reported

Each match has a reason:

| reason                         | applies to                       | meaning                                                                 |
|--------------------------------|----------------------------------|-------------------------------------------------------------------------|
| `transfer`                     | all tracked parties              | the transaction archives a holding of the party and creates one for `receiver`, who is not an expected recipient |
| `acting`                       | parties with `alert_confirmations` | the party is an acting party (controller) of an exercise              |
| `created_signatory`            | parties with `alert_confirmations` | the party is a signatory of a created contract                        |
| `exercised_contract_signatory` | parties with `alert_confirmations` | the party is a signatory of the contract being exercised              |

Holdings are `Amulet` and `LockedAmulet` contracts, so transfers are detected regardless of
how they are made (directly, through a transfer preapproval, or through a token standard
transfer instruction once it is accepted).

The confirmation reasons are stricter than "the party sees the transaction" (being an
observer, e.g. of an incoming transfer offer, does not match), and broader than "the party
submitted the transaction" (actions taken with the party's authority through a delegation do
match).

### Output

One line per matching transaction, e.g.:

```json
{"update_id": "1220...", "migration_id": 4, "record_time": "2026-10-26T10:00:00.123456Z",
 "scan_url": "https://scan.sv-1...", "parties": {"alice::1220...": [
   {"reason": "transfer", "event_id": "1220...:3", "template_id": "...:Splice.Amulet:Amulet",
    "receiver": "mallory::1220...", "amount": "1000.0000000000"}]}}
```

Look up the full transaction with `GET <scan-url>/api/scan/v2/updates/<update_id>`.

Progress, warnings and a periodic status line go to stderr.

### Slack

Create an [incoming webhook](https://api.slack.com/messaging/webhooks) for the channel that
should receive alerts, and pass its URL in `SLACK_WEBHOOK_URL` (preferred, as the URL is a
secret) or `--slack-webhook-url`.

The script posts at most one message per poll of Scan. While it is catching up on a backlog,
it collects the alerts of up to `--slack-batch-seconds` (default 60) into one message. A
message lists a limited number of transactions; stdout always has all of them.

## How it works

Scan's update stream reports the acting parties of an exercise, but not the signatories of
the exercised contract. The script therefore keeps a map from contract id to signatories
(and, for holdings, the owner) of all contracts signed by a tracked party, in the SQLite file
given by `--db`:

1. **Seed.** On first start, the script downloads the tracked parties' contracts from the
   most recent ACS snapshot of the latest migration (`/v2/state/acs`). Every page is saved
   before the next is requested, so you can stop the script at any time; the next start
   resumes from the last saved page.
2. **Stream.** It then follows the update stream (`/v2/updates`) from the snapshot's record
   time onwards, reporting matches and keeping the contract map up to date. Its position is
   saved together with the contract map, so a restart resumes where it stopped.

Note that the script reports matches in all updates after the snapshot, which can be a few
hours before the script was first started.

**Replaying a fixed range.** `--seed-snapshot-before TIME` seeds from the latest snapshot at
or before `TIME` instead of the latest one, and `--stop-at-record-time TIME` exits once all
updates up to `TIME` have been processed. Together, they make a run reproducible, e.g. to
check a configuration against past transactions.

**Adding parties.** The contract map only covers the parties it was built for. When the
configuration adds parties, the script rebuilds the map from the latest snapshot before its
current position and replays the updates from there. During the replay, it reports matches
of the added parties only, as the others' were reported before.

**Delivery.** Alerts are delivered at least once: the position is only saved after the
alerts up to it have been printed and posted to Slack. If the Slack webhook is unreachable
the script keeps retrying rather than skipping alerts. After a crash, the alerts since the
last saved position may be repeated.

## Rate limits

Scan rate-limits each endpoint **across all of its clients** (for `/v2/state/acs` the
default is 5 requests per second for everyone together). The script therefore paces its own
requests: by default 1 ACS request per second while seeding and 2 update requests per second
while catching up, then one poll every 10 seconds. As the seed only fetches the tracked
parties' contracts, it usually takes a few requests.

Please do not raise `--acs-requests-per-second` or `--updates-requests-per-second`: doing so
slows down Scan for everyone else, including other users of this script. If Scan does respond
with HTTP 429, the script backs off and halves its request rate for five minutes.

## Limitations

- Scan only sees the parts of transactions visible to the DSO party, i.e. those that touch
  contracts the DSO party is a stakeholder of, such as Amulet holdings. Transactions that
  involve none of these do not appear in Scan at all.
- Fetches do not appear in Scan's update stream, so being a confirming party only through a
  fetch is not detected.
- If several tracked parties send holdings in one transaction, the outputs cannot be
  attributed to one of them, so each is checked against all outputs; this can report a
  transfer to another sender's expected recipient.
- A transfer funded only from reward coupons, without consuming any of the sender's
  holdings, is not detected as a transfer; it is reported for parties with
  `alert_confirmations`.
- Burning holdings without creating new ones (e.g. to buy traffic) is not a transfer and
  is only reported for parties with `alert_confirmations`.
- Incoming transfers through a `TransferPreapproval` of a party with `alert_confirmations`
  match, because the receiver of such a transfer is a signatory of the preapproval and of
  the new holding, and hence confirms the transfer.
- If the snapshot to seed from disappears from Scan before the seed completes (Scan prunes
  old snapshots), the script stops with an error; delete the database file and start over.
