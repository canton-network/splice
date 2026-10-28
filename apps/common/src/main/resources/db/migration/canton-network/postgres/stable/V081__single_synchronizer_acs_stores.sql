-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

delete from acs_store_template where assigned_domain is null;
delete from validator_acs_store where assigned_domain is null;
delete from user_wallet_acs_store where assigned_domain is null;
delete from external_party_wallet_acs_store where assigned_domain is null;
delete from scan_acs_store where assigned_domain is null;
delete from sv_acs_store where assigned_domain is null;
delete from dso_acs_store where assigned_domain is null;
delete from splitwell_acs_store where assigned_domain is null;
delete from scan_rewards_reference_store_active where assigned_domain is null;

alter table acs_store_template
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table validator_acs_store
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table user_wallet_acs_store
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table external_party_wallet_acs_store
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table scan_acs_store
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table sv_acs_store
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table dso_acs_store
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table splitwell_acs_store
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table scan_rewards_reference_store_active
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

alter table scan_rewards_reference_store_archived
    drop column state_number,
    drop column assigned_domain,
    drop column reassignment_counter,
    drop column reassignment_target_domain,
    drop column reassignment_source_domain,
    drop column reassignment_submitter,
    drop column reassignment_unassign_id;

drop table incomplete_reassignments;
