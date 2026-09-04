alter table scan_verdict_store
    -- adds a nullable round_number column so the addition does not require a hard migration
    add column round_number          bigint null;
