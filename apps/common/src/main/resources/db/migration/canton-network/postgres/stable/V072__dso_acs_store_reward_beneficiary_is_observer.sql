-- For storing RewardCouponV2's provider/beneficiary is observer
alter table dso_acs_store
  add column reward_beneficiary_is_observer boolean;
