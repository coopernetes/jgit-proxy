-- Provider columns now store type/host (e.g. "github/github.com") instead of
-- bare type (e.g. "github").  Update existing rows to the new format.
-- Column widening is handled by a vendor-specific companion migration
-- (db/migration-postgresql/V2_1__widen_provider_columns.sql) for databases that need it.

UPDATE push_records         SET provider = 'github/github.com' WHERE provider = 'github';
UPDATE user_scm_identities  SET provider = 'github/github.com' WHERE provider = 'github';
UPDATE access_rules         SET provider = 'github/github.com' WHERE provider = 'github';
UPDATE fetch_records        SET provider = 'github/github.com' WHERE provider = 'github';
UPDATE scm_token_cache      SET provider = 'github/github.com' WHERE provider = 'github';
UPDATE repo_permissions     SET provider = 'github/github.com' WHERE provider = 'github';
