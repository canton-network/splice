# seqconn group: 11219

- Verdict: new product bug, ReconcileSequencerConnectionsTrigger ignores trustedSynchronizerConfig.threshold (init path
  from #3512 applies it, trigger does not). Packet evidence/11219-validator-trusted-threshold-reset-by-reconcile-trigger.md.
- Action: comment, retitle, assign pasindutennage-da (#3512), Triaged.
- Logs: log/11219/ (job.log, art/, suite.clog, Trigger.scala, SynchronizerConnector.scala at 121e286893).
