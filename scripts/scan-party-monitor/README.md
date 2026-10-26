# Scan party monitor

`scan_party_monitor.py` watches a Scan instance and reports every transaction in which
one of a given set of parties is a **confirming party**. For each node of a transaction,
the party matches if:

| reason                         | meaning                                                   |
|--------------------------------|-----------------------------------------------------------|
| `acting`                       | the party is an acting party (controller) of an exercise  |
| `created_signatory`            | the party is a signatory of a created contract            |
| `exercised_contract_signatory` | the party is a signatory of the contract being exercised  |

This is stricter than "the party sees the transaction" (being an observer, e.g. of an
incoming transfer offer, does not match), and broader than "the party submitted the
transaction" (actions taken with the party's authority through a delegation do match).

Matches are printed to stdout as one JSON object per transaction, and optionally posted to
a Slack channel through an incoming webhook.

The script only needs Python 3 and its standard library.

## Usage

```bash
export SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...   # optional
./scan_party_monitor.py \
  --scan-url https://scan.sv-1.<network-host> \
  --db ./party-monitor.sqlite \
  --party 'my-party::1220...' \
  --party 'my-other-party::1220...'
```

Parties can also be listed in a file with `--parties-file` (one party id per line, `#`
starts a comment). Run with `-h` for all options.

Scan's public API is only reachable from IP addresses that the SV operating the Scan
instance has allowlisted. Use a Scan instance of an SV you work with.

### Output

One line per matching transaction, e.g.:

```json
{"update_id": "1220...", "migration_id": 4, "record_time": "2026-10-26T10:00:00.123456Z",
 "scan_url": "https://scan.sv-1...", "parties": {"my-party::1220...": [
   {"reason": "acting", "event_id": "1220...:0", "template_id": "...:Splice.AmuletRules:AmuletRules",
    "choice": "AmuletRules_Transfer"}]}}
```

Look up the full transaction with `GET <scan-url>/api/scan/v2/updates/<update_id>`.

Progress, warnings and a periodic status line go to stderr.

### Slack

Create an [incoming webhook](https://api.slack.com/messaging/webhooks) for the channel that
should receive alerts, and pass its URL in `SLACK_WEBHOOK_URL` (preferred, as the URL is a
secret) or `--slack-webhook-url`. Each matching transaction produces one message.

## How it works

Scan's update stream reports the acting parties of an exercise, but not the signatories of
the exercised contract. The script therefore keeps a map from contract id to signatories for
**all** contracts known to Scan in the SQLite file given by `--db`:

1. **Seed.** On first start, the script downloads the most recent ACS snapshot of the
   latest migration from Scan (`/v2/state/acs`). Every page is saved before the next is
   requested, so you can stop the script at any time; the next start resumes from the last
   saved page. Progress is logged every 10 seconds; since the ACS is sorted by contract
   creation time, the "reached contracts created at" timestamp shows how far along it is.
2. **Stream.** It then follows the update stream (`/v2/updates`) from the snapshot's record
   time onwards, reporting matches and keeping the contract map up to date. Its position is
   saved after each page of updates, so a restart resumes where it stopped.

Since all contracts are stored, you can restart with a different set of parties at any time
and the new parties are fully covered from that point on.

Alerts are delivered at least once: the position is only saved after the matches of a page
have been printed and posted to Slack. If the Slack webhook is unreachable the script keeps
retrying rather than skipping alerts. After a crash, the last page's alerts may be repeated.

Note that the script reports matches in all updates after the snapshot, which can be a few
hours before the script was first started.

## Rate limits

Scan rate-limits each endpoint **across all of its clients** (for `/v2/state/acs` the
default is 5 requests per second for everyone together). The script therefore paces its own
requests: by default 1 ACS request per second while seeding and 2 update requests per second
while catching up, then one poll every 10 seconds. At the default page size of 1000 the seed
proceeds at about 3.6 million contracts per hour.

Please do not raise `--acs-requests-per-second` or `--updates-requests-per-second`: doing so
slows down Scan for everyone else, including other users of this script. If Scan does respond
with HTTP 429, the script backs off and halves its request rate for five minutes.

## Limitations

- Scan only sees the parts of transactions visible to the DSO party, i.e. those that touch
  contracts the DSO party is a stakeholder of, such as Amulet holdings. Transactions that
  involve none of these do not appear in Scan at all.
- Fetches do not appear in Scan's update stream, so being a confirming party only through a
  fetch is not detected.
- Incoming transfers through a `TransferPreapproval` of a watched party match, because the
  receiver of such a transfer is a signatory of the preapproval and of the new holding, and
  hence confirms the transfer.
- If the stored snapshot disappears from Scan before the seed completes (Scan prunes old
  snapshots), the script stops with an error; delete the database file and start over.
