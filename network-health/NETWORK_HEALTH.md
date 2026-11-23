# Network Health

This document describes how to analyse network health and track down
potential issues. It is intended to serve as guidance to anyone
investigating alerts or support requests.

## Background

There are 5 layers that all need to function for a network of 3f+1 SVs to be healthy overall:

1. Sequencers (2f+1 need to work because otherwise the SV’s participant and mediator behind them are not healthy, for each validator f+1 need to work for its participant to advance)
2. Mediators (f+1 need to work for all transactions)
3. Participants (2f+1 need to work for any transaction with the DSO as a confirmer)
4. SV apps (2f+1 need to work for rounds to advance, however transactions even with DSO confirmer can go through without SV apps functioning)
5. Scan (f+1 need to work for each validator to be able to read data through explicit disclosure and perform amulet operations)


## Alerting

The main alerts that indicate issues in overall network health are the SV status report alerts.

### SV Status Reports

SV status reports are contracts created periodically by automation in the SV app. They have the DSO as a signatory so
require confirmation from 2f+1 SV participants. They include the time at which the SV created it as well as
a high-level view of the SV’s state including domain time and latest mining round.

The alerts are usually of the form

```
 SV Status - Unexpected Mining Round Lag
 Unexpected mining round lag 14 in SV status reports for publisher Liberty-City-Ventures
```

```
SV Status -  Node falling behind
 Domain Time progressing too slowly
 The SVs' Liberty-City-Ventures status reports for mediator  shows that their node is falling behind wrt processing messages from the domain
```

## Alert Investigations

### SV Status Issues

As described above, SV status report alerts indicate that at least some SVs are unable to submit their status reports.
The first point of investigation is to check whether this is a network wide issue and no SV can submit their status reports
or whether it affects only some SVs.

This is easiest to do using the
[SV Status Report Dashboard](https://grafana.dev.global.canton.network.digitalasset.com/d/caffa6f7-c421-4579-a839-b026d3b76826/sv-status-reports?orgId=1)
(adjust cluster as needed).

An SV local issue shows up as one SV’s report creation time not advancing while other SVs report creation time is still advancing.
In the screenshot below you can see Cumberland-2 not advancing for a while while for other SVs the report creation time does advance.

![SBI not advancing](pics/status_report_one_sv.png)

In this screenshot on the other hand, all SVs are not advancing.

![All SVs not advancing](pics/status_report_all_svs.png)

Note that the metric is exposed from our SV app so if our SV app is down, we won't see any metrics.

Depending on whether it is a network-wide issue or an SV local issue, investigations procees slightly differently:

#### SV Local Issues

For an SV local issue, the main thing to investigae is whether just the SV app is broken or whether the underlying Canton components might also be broken.

As a general rule, when a component is (suspected to be) failing, look for the earliest error message you can find in its logs.
(Filter the logs by severity `>= WARNING`.)
Sometimes a single error can completely brick a component, with no relevant follow-up log messages, so be sure to look sufficiently far back in time.

The easiest way of checking this is to check the periodic acknowledgements from their participant and mediator. This is a message each node
sends out to acknowledge that it has seen messages up to a certain timestamp. This allows both to see whether the node is active at all (i.e. has it stopped sending out acknowledgements) and whether it as lagging behind.

The acknowlegdements can be seen in the [global domain catchup dashboard](https://grafana.dev.global.canton.network.digitalasset.com/d/ca9df344-c699-4efe-83c2-5fb2639d96d9/global-domain-catchup?orgId=1&refresh=30s&var-DS=prometheus&var-namespace=sv-1&var-migration=All&var-member=All) (tip: you can filter by the ID of the sender at the top).
Particularly useful are the lag and the catchup speed which let you estimate how long it will take for a node to catchup.

![Cumberland lagging](pics/acknowledgement_dashboard.png)


If you need more detailed information, you can also look at the sequencer logs.
For example, in the logs below you can see SBI acknowledging timestamp `2024-04-29T05:06:06.692783Z` at `2024-04-29 05:35:41.170` so it is roughly lagging 30min behind but the participant is active. A delay of a few seconds is expected, anything larger than a minute will mean that this participant is unable to confirm
transactions because it sees the confirmation only after the configured `confirmationResponseTimeout`.

![SBI lagging](pics/acknowledgement_lag.png)

If you do see a lag, you want to check:

1. Is it catching up at all or just completely stuck / not completing any acknowledgements? If it is completely stuck, the reason might be a fatal error in the participant. Scan the participant logs for errors and warnings (see general rule above).

2. Has there been a sudden traffic spike that might have overloaded their sequencer? This is easiest to do using the [sequencer traffic dashboard](https://grafana.dev.global.canton.network.digitalasset.com/d/fdjrxql2alblsd/sequencer-traffic?orgId=1) (adjust cluster as needed). E.g., here we can see a spike starting at 6:50

![domain traffic spike](pics/sequencer_traffic_spike.png)
3. Are they catching up faster than realtime, i.e., do their acknowledgements advance within 10 minutes by more than 10 minutes and they will eventually catch up or do they fall further and further behind. E.g., in the screenshot here we can see that within 10 minutes, the acknowledgements only advance from `2024-04-29T05:02:33.813218Z` to `2024-04-29T05:04:32.392203Z` so SBI is falling further and further behind. Note that even if they can eventually catch up, we likely want to follow up with them to make sure they improve performance of our nodes.

![sbi lagging further and further](pics/acknowledgement_falling_behind.png)

It is often also useful to compare this to the domain catchup metrics of our own nodes to see if the overload was specific to their node. This is best done using the [sequencer client delay](https://grafana.dev.global.canton.network.digitalasset.com/d/ca9df344-c699-4efe-83c2-5fb2639d96c9/global-domain-catchup?orgId=1&refresh=30s&var-DS=prometheus&var-namespace=sv-1&from=now-12h&to=now&viewPanel=1) graph in the domain catchup dashboard.

![sequencer_client_delay](pics/sequencer_client_delay.png)

If the SV appears to be up2date according to acknowledgements but you do not
see status reports, check if you see other transactions from it. This is easiest
by anaylzing the `Ingested transaction` logs from our SV app. You should see among other things
reward coupon creations and confirmations for round operations.

![SV ingested transaction](pics/sv_ingested_transaction.png)

If you do see them, the SV app itself is active
but there might be an issue specific to submitting status reports. One possible cause of this can be
the mediator being unavailable since that is queried when submitting a status report. You can check that by looking at the traffic submitted by that mediator in the [sequencer traffic dashboard](https://grafana.dev.global.canton.network.digitalasset.com/d/fdjrxql2alblsd/sequencer-traffic?orgId=1&from=1714363043128&to=1714373918128). In the example here, Cumberland-2's mediator was unavailable and did not submit anything.

![Mediator traffic](pics/mediator_traffic.png)

#### Network Wide Issues

Network wide issues can come from any of the layers involved in the daml transaction of submitting a status report:

1. Participants are unable to submit to sequencers.
2. Participants are unable to read the confirmation request.
3. Participants are unable to submit confirmation requests.
4. Mediators are unable to read the confirmation request.
5. Mediators don't get enough confirmations before `confirmationResponseTimeout`
6. Sequencers do not receive enough mediator responses before `mediatorResponseTimeout`
7. SV apps are not submitting status reports

A good way of starting here is from the top: Find a log of our SV app trying to submit a status report. Then trace through the logs
and figure out where it might get stuck. The logs can easily get overwhelming so as a starting point it is often useful to filter it down to only the different
Canton phases. For a successful transaction you will see all 7 phases.

![successful daml tx](pics/canton_phase_logs.png)

The primary cause of failure is usually a mediator timeout because it receives an insufficient number of confirmations.
In the following screenshot, you can see Cumberland-2 not confirming.

![mediator timeout](pics/canton_mediator_timeout.png)

Once you pinned down the SV, you can again check their acknowledgements to see if their nodes are active at all and lagging behind or completely dead.

Other failure types are usually more rare and require case by case analysis based on the logs.

##### Recap: Canton phases

1. Participant submits
2. Mediator receives confirmation request
3. Participant receives confirmation request
4. Participant submits confirmation
5. Mediator receives confirmation
6. Mediator reaches verdict
7. Participant receives transaction accepted/rejected.
