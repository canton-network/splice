alter table scan_verdict_store
    -- adds a nullable round_number column so the addition does not require rewriting the whole table
    add column round_number          bigint null;
