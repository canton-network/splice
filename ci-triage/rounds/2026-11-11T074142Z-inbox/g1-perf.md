# g1-perf: 11351 11352

Verdict: 11351 dup 11321, 11352 dup 11322 (both open, Triaged, assigned jagathweerasinghe-da). Comment on 11322 corrects the cause (KeyError on missing serializeJson rule, #8351), not the ingestion breach.

## INDEX.tsv rows

```
11351	2026-11-11	42954358024	main 9dd892fe2e	130305812492 Ingestion Performance Tests		Daily store perf ingestion: job success, report step filed on breaches; VerdictStore avg_item_time 11835193 > 3700000 (+219.87%, seed from #8376), UpdateHistory 34014774 > 33100000 (+2.76%, new), 04:03:52Z	11321	config	close dup 11321	evidence/11351-11352-perf-thresholds-verdict-seed-serializejson-rule.md
11352	2026-11-11	42954358024	main 9dd892fe2e	130313819699 Read Performance Tests		Daily store perf read: Check read thresholds KeyError, no rule for UpdateHistoryReadPerformanceTest-serializeJson (added by #8351, 2026-11-06), 04:16:22Z; same on 11-10	11322	config	close dup 11322; correction comment on 11322	evidence/11351-11352-perf-thresholds-verdict-seed-serializejson-rule.md
```

Known families: none (config gap, owner assigned).

Lesson: none (a new metric without a threshold rule is already the subject of 11280; candidate only if it recurs a third time: a check that raises on a missing rule masks every file after it).
