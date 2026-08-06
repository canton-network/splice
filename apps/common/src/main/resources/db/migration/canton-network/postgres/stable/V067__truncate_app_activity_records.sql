-- Truncate app activity record store and all downstream reward-accounting tables.
-- The app activity computation has been modified to exclude the SV submitted transactions,
-- and prior records may contain records for those transactions.
truncate table app_activity_record_store,
               app_activity_party_totals,
               app_activity_round_totals,
               app_reward_party_totals,
               app_reward_round_totals,
               app_reward_batch_hashes,
               app_reward_root_hashes;
