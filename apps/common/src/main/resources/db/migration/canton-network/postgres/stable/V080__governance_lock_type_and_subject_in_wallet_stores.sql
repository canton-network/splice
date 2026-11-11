alter table user_wallet_acs_store
  add column governance_lock_type text,
  add column governance_lock_subject text;

alter table external_party_wallet_acs_store
  add column governance_lock_type text,
  add column governance_lock_subject text;
