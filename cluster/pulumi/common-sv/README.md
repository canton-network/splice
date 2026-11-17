# common-sv (Pulumi project)

Common Pulumi code for deploying SVs, as well as utility functions that tightly depend on SV-related configs.

## Enabling the Datastream API

Scan's BigQuery replication uses GCP Datastream, whose API must be
enabled once per cluster project, by hand. If a Pulumi update fails
with an error like

    gcp:datastream:ConnectionProfile (sv-4-scan-bq-cxn):
      error: ... Error 403: Datastream API has not been used in
      project <project> before or it is disabled. Enable it by
      visiting https://console.developers.google.com/apis/api/datastream.googleapis.com/overview?project=<project>
      then retry.

(or the same for a `gcp:datastream:PrivateConnection`), enable the API
for that project as the message describes, wait a few minutes for it to
propagate, and retry.
