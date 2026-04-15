-- For storing RewardCouponV2's provider/beneficiary is observer
alter table dso_acs_store
  add column reward_beneficiary_is_observer boolean;

create index dso_acs_store_sid_mid_pn_tid_rbio
    on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, reward_party)
    where reward_beneficiary_is_observer = false;
