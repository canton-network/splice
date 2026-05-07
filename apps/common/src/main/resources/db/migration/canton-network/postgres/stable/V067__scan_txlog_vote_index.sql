-- Supports listVoteRequestResultsQuery: ORDER BY entry_number DESC LIMIT N
-- with optional cursor (entry_number < $after). Without this index, the
-- planner picks an Index Scan Backward over the primary key on entry_number,
-- filtering out non-vote rows one by one
create index scan_txlog_store_sid_en_vot
    on scan_txlog_store (store_id, entry_number desc)
    where entry_type = 'vot';
