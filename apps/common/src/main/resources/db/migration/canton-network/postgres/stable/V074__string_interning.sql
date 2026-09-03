create table interned_strings
(
    id    bigint generated always as identity primary key,
    value text not null unique
);
