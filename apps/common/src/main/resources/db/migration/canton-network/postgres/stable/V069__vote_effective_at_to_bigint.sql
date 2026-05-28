alter table scan_txlog_store
    alter column vote_effective_at type bigint
    using case
        when vote_effective_at is null then null
        else (extract(epoch from vote_effective_at::timestamptz) * 1000000)::bigint
    end;