
### Implementation note

Scan keeps one index for the lookup API. The only query is an exact `property`, then optional `issuer`, `holder`, and `subject` in that order. There is no property-prefix lookup.

`issuer` and `holder` are fields of the credential record. `property` and `subject` are fields of a claim, so the index is over claim rows. An unset subject means the holder.

```
create index idx_credentialrecord_lookup on credential_claim (
  property,
  issuer,
  holder,
  coalesce(subject, holder),
  record_time desc,
  contract_id asc
);
```

That column order matches the result order: `record_time` descending, `contract_id` ascending. The first row in a duplicate group is the last-write-wins record.


#### On deterministic responses when reading from multiple registry services

The Scan proxy should implement a BFT read strategy where it queries
multiple scan endpoints. The following tricks might help to achieve
deterministic responses:

- Determine a record_time cutoff based on discretizing record time
  and choosing the end of a previous time slice to query up to.

