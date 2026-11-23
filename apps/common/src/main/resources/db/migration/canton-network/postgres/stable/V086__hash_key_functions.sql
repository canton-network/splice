-- Functions that derive a fixed-size key from a text or jsonb value, for indexing variable-length columns by a hash
-- of their value instead of the value itself.
-- Queries should filter on both the key (to use the index) and the raw column (to recheck the value).

-- The first 16 bytes of sha256(utf8(value)), as uuid.
-- `convert_to` is only marked stable, but its result is fixed for a given database encoding,
-- so declaring the function immutable is safe and required for using it in an index.
create function splice_text_key(value text) returns uuid
  language sql immutable strict parallel safe
  as $$ select encode(substring(sha256(convert_to(value, 'UTF8')) from 1 for 16), 'hex')::uuid $$;

-- The built-in jsonb hash, which is consistent with jsonb equality: equal values have the same key,
-- including numbers that are equal but written with a different scale (1.0 and 1.00).
create function splice_jsonb_key(value jsonb) returns bigint
  language sql immutable strict parallel safe
  as $$ select jsonb_hash_extended(value, 0) $$;
