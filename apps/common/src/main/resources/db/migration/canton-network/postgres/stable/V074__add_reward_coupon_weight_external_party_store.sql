alter table external_party_wallet_acs_store
    add column reward_coupon_weight bigint null;


create index external_party_wallet_acs_store_sid_mid_tid_rcw
    on external_party_wallet_acs_store (store_id, migration_id, template_id_qualified_name, reward_coupon_weight)
    where reward_coupon_weight is not null;
