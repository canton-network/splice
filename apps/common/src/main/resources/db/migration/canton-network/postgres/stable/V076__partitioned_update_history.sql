-- Keep all data that predates partitioning in a default legacy partition.
-- New partitions can be added later without copying the existing tables.

alter table update_history_transactions
    rename to update_history_transactions_legacy;
create table update_history_transactions
    (like update_history_transactions_legacy
        including defaults
        including generated
        including identity
        including storage
        including comments)
    partition by range (record_time);
alter table update_history_transactions_legacy
    alter column row_id drop identity;
alter table update_history_transactions
    attach partition update_history_transactions_legacy default;
select setval(
    pg_get_serial_sequence('update_history_transactions', 'row_id'),
    coalesce((select max(row_id) from update_history_transactions), 1)
);

alter table update_history_creates
    rename to update_history_creates_legacy;
create table update_history_creates
    (like update_history_creates_legacy
        including defaults
        including generated
        including identity
        including storage
        including comments)
    partition by range (record_time);
alter table update_history_creates_legacy
    alter column row_id drop identity;
alter table update_history_creates
    attach partition update_history_creates_legacy default;
select setval(
    pg_get_serial_sequence('update_history_creates', 'row_id'),
    coalesce((select max(row_id) from update_history_creates), 1)
);

alter table update_history_exercises
    rename to update_history_exercises_legacy;
create table update_history_exercises
    (like update_history_exercises_legacy
        including defaults
        including generated
        including identity
        including storage
        including comments)
    partition by range (record_time);
alter table update_history_exercises_legacy
    alter column row_id drop identity;
alter table update_history_exercises
    attach partition update_history_exercises_legacy default;
select setval(
    pg_get_serial_sequence('update_history_exercises', 'row_id'),
    coalesce((select max(row_id) from update_history_exercises), 1)
);

alter table update_history_assignments
    rename to update_history_assignments_legacy;
create table update_history_assignments
    (like update_history_assignments_legacy
        including defaults
        including generated
        including identity
        including storage
        including comments)
    partition by range (record_time);
alter table update_history_assignments_legacy
    alter column row_id drop identity;
alter table update_history_assignments
    attach partition update_history_assignments_legacy default;
select setval(
    pg_get_serial_sequence('update_history_assignments', 'row_id'),
    coalesce((select max(row_id) from update_history_assignments), 1)
);

alter table update_history_unassignments
    rename to update_history_unassignments_legacy;
create table update_history_unassignments
    (like update_history_unassignments_legacy
        including defaults
        including generated
        including identity
        including storage
        including comments)
    partition by range (record_time);
alter table update_history_unassignments_legacy
    alter column row_id drop identity;
alter table update_history_unassignments
    attach partition update_history_unassignments_legacy default;
select setval(
    pg_get_serial_sequence('update_history_unassignments', 'row_id'),
    coalesce((select max(row_id) from update_history_unassignments), 1)
);