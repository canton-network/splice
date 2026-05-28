-- Add index for sv_party to the dso_acs_store.

CREATE INDEX dso_acs_store_sv_party_idx
    ON dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, sv_party)
    WHERE sv_party IS NOT NULL;
