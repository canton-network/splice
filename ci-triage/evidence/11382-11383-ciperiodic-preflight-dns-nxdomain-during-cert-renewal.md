# 11382, 11383 - deploy_upgrade (ciperiodic-base) preflights: cluster hostnames fail to resolve while cert-manager renews the cluster certificate

Verdict: new, infra (DNS for the ciperiodic cluster). Both are the same cause in one pipeline (88876), not app or test
failures. Hostnames under one SV ingress (`*.sv-1.ciperiodic...`, `*.sv-2.ciperiodic...`) stopped resolving from the
CircleCI runners for one to two minutes, between 10:58 and 11:00 UTC. That is inside the scheduled renewal of the
cluster certificate (10:54:41 to 11:05:26), which uses ACME DNS-01 challenges in Cloud DNS. Proposed mechanism
(timing confirmed, DNS records not seen): a challenge TXT record `_acme-challenge.<ingress>.ciperiodic...` turns
`<ingress>.ciperiodic...` into an existing (empty non-terminal) name, so the zone wildcard `*.ciperiodic...` no longer
applies to names below it (RFC 4592) and they return NXDOMAIN until the challenge is cleaned up.

## 0. Issues and jobs

Both filed 2026-11-12 for CircleCI pipeline 88876, workflow deploy_upgrade, branch build/ciperiodic-base, cni commit
03fd333 "Update config for deployment to ciperiodic" (issue bodies, `gh api repos/DACH-NY/cn-test-failures/issues/<ref>`).
- 11382: job 531504 `preflight_validator_initial`, step 050 `Run Validator preflight tests`.
- 11383: job 531501 `preflight_check_initial`, step 048 `Executing "sbt "$RUN_SPLITTED_TESTS_CMD""`.
Exports by the user (host): `export_circleci.py 11382 531504 --artifacts`, `export_circleci.py 11383 531501 --artifacts`,
`export_gcp_logs.py 11383 ... --query warn ... --query sv-app ... --query seq-p2p ...` (10:20-11:05) and
`export_gcp_logs.py 11383 ... --start 2026-11-12T10:00:00Z --end 2026-11-12T11:10:00Z --query cert-manager 'resource.labels.namespace_name="cert-manager"'`.
`E=ci-triage/export`. The exports are gitignored and stay on the machine of whoever ran them; rerun the export commands above to recreate them (GCP logs only while Cloud Logging still holds them).

## 1. Failures

11383 (`DsoPreflightIntegrationTest`):
```
$ zcat $E/11383/531501-artifacts/splice/log/canton_network_test.clog.gz | grep -a -E 'Running clue: Checking SV at|Finished clue: Checking SV at|Failed clue: Checking SV at|Test failed: .DsoPreflightIntegrationTest/SVs' | (format)
2026-11-12T10:57:20.378Z Running clue: Checking SV at https://sv.sv-2-eng.ciperiodic.network.canton.global",
2026-11-12T10:58:20.333Z Finished clue: Checking SV at https://sv.sv-2-eng.ciperiodic.network.canton.global",
2026-11-12T10:58:20.333Z Running clue: Checking SV at https://sv.sv-2.ciperiodic.network.canton.global",
2026-11-12T10:58:20.426Z Finished clue: Checking SV at https://sv.sv-2.ciperiodic.network.canton.global",
2026-11-12T10:58:20.426Z Running clue: Checking SV at https://sv.sv-1.ciperiodic.network.canton.global",
2026-11-12T11:00:20.433Z Failed clue: Checking SV at https://sv.sv-1.ciperiodic.network.canton.global",
2026-11-12T11:00:20.455Z Test failed: 'DsoPreflightIntegrationTest/SVs 1-3 + DA-1 report ready', message: false was not equal to true, location: SeeStackDepthException",
```
11382 (`RunbookValidatorPreflightIntegrationTest`), same host family resolving and then not:
```
$ zcat $E/11382/531504-artifacts/splice/log/canton_network_test.clog.gz | grep -a -E 'scan\.sv-2\.|HTTP client \(GET /api/scan/v0/(splice-instance|dso-sequencers)|CONSOLE_COMMAND_INTERNAL_ERROR|Test failed:' | grep -a -E 'T10:5(8:4[5-9]|8:5|9:4)' | (format)
2026-11-12T10:58:45.431Z Running on sv1Scan command GetSpliceInstanceNames() against NetworkAppClientConfig(https://scan.sv-2.ciperiodic.network.canton.global,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes)))",
2026-11-12T10:58:45.516Z HTTP client (GET /api/scan/v0/splice-instance-n...): HTTP request took 81 ms to complete. Received response with status code: 200 OK",
2026-11-12T10:58:45.548Z Running on sv1Scan command GetSpliceInstanceNames() against NetworkAppClientConfig(https://scan.sv-2.ciperiodic.network.canton.global,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes)))",
2026-11-12T10:58:45.568Z HTTP client (GET /api/scan/v0/splice-instance-n...): HTTP request took 15 ms to complete. Received response with status code: 200 OK",
2026-11-12T10:58:52.063Z Running on sv1Scan command GetSpliceInstanceNames() against NetworkAppClientConfig(https://scan.sv-2.ciperiodic.network.canton.global,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes)))",
2026-11-12T10:58:52.080Z HTTP client (GET /api/scan/v0/splice-instance-n...): HTTP request took 14 ms to complete. Received response with status code: 200 OK",
2026-11-12T10:58:52.189Z Running on sv1Scan command GetSpliceInstanceNames() against NetworkAppClientConfig(https://scan.sv-2.ciperiodic.network.canton.global,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes)))",
2026-11-12T10:58:52.205Z HTTP client (GET /api/scan/v0/splice-instance-n...): HTTP request took 13 ms to complete. Received response with status code: 200 OK",
2026-11-12T10:58:52.208Z Running on sv1Scan command GetSpliceInstanceNames() against NetworkAppClientConfig(https://scan.sv-2.ciperiodic.network.canton.global,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes)))",
2026-11-12T10:58:52.225Z HTTP client (GET /api/scan/v0/splice-instance-n...): HTTP request took 14 ms to complete. Received response with status code: 200 OK",
2026-11-12T10:59:45.147Z Running on sv1Scan command ListDsoSequencers() against NetworkAppClientConfig(https://scan.sv-2.ciperiodic.network.canton.global,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes)))",
2026-11-12T10:59:45.275Z HTTP client (GET /api/scan/v0/dso-sequencers): HTTP request failed",
2026-11-12T10:59:45.293Z CONSOLE_COMMAND_INTERNAL_ERROR(4,0): An internal error has occurred while running a console command.",
2026-11-12T10:59:45.304Z Test failed: 'RunbookValidatorPreflightIntegrationTest/connect to all sequencers stated in latest DsoRules contract', message: Command execution failed due to an internal error. Please file a bug report., location: SeeStackDepthExcepti
```
The stack traces of the failed requests (`"stack_trace"` of the same lines):
`java.net.UnknownHostException: sv.sv-1.ciperiodic.network.canton.global` (11383) and
`java.net.UnknownHostException: scan.sv-2.ciperiodic.network.canton.global` (11382).

## 2. Every UnknownHostException in both jobs (first, last, lines, host)

```
$ for r in 11383/531501 11382/531504; do zcat $E/${r%/*}/${r#*/}-artifacts/splice/log/canton_network_test.clog.gz | grep -a UnknownHostException | awk (first/last/count per host); done
== 11383/531501
2026-11-12T10:58:20.698Z 2026-11-12T11:00:20.432Z 74 sv.sv-1.ciperiodic.network.canton.global
== 11382/531504
2026-11-12T10:59:45.275Z 2026-11-12T10:59:45.293Z 2 scan.sv-2.ciperiodic.network.canton.global
```
(Two lines per failed request: the HTTP client DEBUG line and the error line.) Only these two hosts, only between
10:58:20 and 11:00:20. sv-2-eng and sv-2 (`sv.` hosts) resolved at 10:58:20; `scan.sv-2` resolved at 10:58:52.

## 3. cert-manager renewed the cluster certificate in exactly that window

```
$ jq -r '.[] | [.timestamp, ..., (.jsonPayload.msg // ...), ...] | @tsv' $E/11383/gcp/cert-manager.json | cut -f1,5 | grep -E 'Certificate must be re-issued|certificate issued'
2026-11-12T10:54:41.000977687Z	"Certificate must be re-issued" logger="cert-manager.controller" key="cluster-ingress/cn-ciperiodic-certificate" reason="Renewing" message="Renewing certificate as renewal was scheduled at 2026-11-12 10:54:41 +0000 UTC"
2026-11-12T11:05:26.263611925Z	"certificate issued" logger="cert-manager.controller.sign" resource_name="cn-ciperiodic-certificate-17" resource_namespace="cluster-ingress" resource_kind="CertificateRequest" resource_version="v1" related_resource_name="cn-ciper
```
kubectl output pasted by the user (2026-11-12 ~17:35 UTC; 6h40m before is about 10:55):
```
NAMESPACE         NAME                                                    READY   SECRET                 ISSUER                   STATUS                                          AGE
cluster-ingress   certificate.cert-manager.io/cn-ciperiodic-certificate   True    cn-ciperiodicnet-tls   letsencrypt-production   Certificate is up to date and has not expired   626d
cluster-ingress   certificaterequest.cert-manager.io/cn-ciperiodic-certificate-17   True                True    letsencrypt-production   system:serviceaccount:cert-manager:cert-manager-helm-edb308ae   Certificate fetched from issuer successfully   6h40m
cluster-ingress   order.acme.cert-manager.io/cn-ciperiodic-certificate-17-556937007   valid   letsencrypt-production            6h40m
```
The renewal was time-based ("renewal was scheduled"), not caused by the deployment.

## 4. Zone layout and certificate names (splice main, cluster/pulumi/infra/src/network.ts)

```
$ git show origin/main:cluster/pulumi/infra/src/network.ts | sed -n 32,66p      (clusterDnsEntries: the only two records)
    new gcp.dns.RecordSet(dnsName, { name: dnsName + '.', ttl: 60, type: 'A', ... rrdatas: [ingressIp.address] })
    new gcp.dns.RecordSet(dnsName + '-subdomains', { name: `*.${dnsName}.`, ttl: 60, type: 'A', ... })
$ git show origin/main:cluster/pulumi/infra/src/network.ts | sed -n 140,155p     (issuer: ACME dns01 via cloudDNS, project da-gcp-canton-domain)
$ git show origin/main:cluster/pulumi/infra/src/network.ts | sed -n 189,196p     (certificate dnsNames)
        `${dnsName}`,
        `*.${dnsName}`,
        ...allValidatorIngressNames.map(ingress => `*.${ingress}.${dnsName}`),
        `*.validator1.${dnsName}`,
        `*.splitwell.${dnsName}`,
        `*.sv.${dnsName}`,
      ].concat(svConfigsBasic.map(sv => `*.${sv.ingressName}.${dnsName}`))
```
(The two RecordSet lines are condensed from lines 42-64; the full text is in the file.) The DNS-01 challenge for
`*.sv-1.<cluster>` is a TXT record at `_acme-challenge.sv-1.<cluster>`.

## 5. Earlier occurrence (not verified)

```
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "UnknownHostException"' --jq '.items[] | ...'
7558 closed 2026-03-17 cimain: UnknownHostException in test / Unable to resolve host in cluster logs
```
7558: `wallet.validator.cimain.global.canton.network.digitalasset.com` did not resolve at 2026-03-17 10:55:30 in a
preflight; closed after a month as "network issues". Same shape (a host under an ingress subdomain), cause not checked.

## Verdict

- Cause (timing verified, record not seen): DNS-01 challenge records for the per-ingress wildcard names make the
  ingress subdomain an existing name, which disables the zone wildcard below it until cleanup. Every lookup failure in
  both jobs is inside the renewal window 10:54:41-11:05:26 and is for names under one SV ingress.
- Not verified: the TXT records themselves and their timestamps (Cloud DNS audit log in project
  da-gcp-canton-domain, zone of ciperiodic.network.canton.global), and whether other names failed during the renewal.
- Fix (infra, cluster/pulumi/infra/src/network.ts `clusterDnsEntries`): add an explicit wildcard A record per ingress
  subdomain that has a wildcard name in the certificate (`*.<ingress>.<cluster>` for the SV, validator, splitwell and
  sv ingress names), so a challenge record cannot hide the names below it. Alternative: make the preflights retry name
  resolution for longer than the renewal (worse: it only hides the outage).
- Frequency: renewals are scheduled per cluster (ciperiodic: certificate request revision 17 after 626 days), so this is
  rare per cluster, but any CI or user traffic to the affected names in the renewal window (here 11 min) fails.
