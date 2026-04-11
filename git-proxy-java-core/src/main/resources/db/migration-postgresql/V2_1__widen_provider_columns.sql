-- Widen provider columns to accommodate type/host values where host can be up
-- to 253 characters (RFC 1035).  PostgreSQL-specific syntax; H2 (dev/test) uses
-- VARCHAR(100) from V1 and does not need widening (always a fresh schema).

ALTER TABLE push_records        ALTER COLUMN provider TYPE VARCHAR(300);
ALTER TABLE user_scm_identities ALTER COLUMN provider TYPE VARCHAR(300);
ALTER TABLE access_rules        ALTER COLUMN provider TYPE VARCHAR(300);
ALTER TABLE fetch_records       ALTER COLUMN provider TYPE VARCHAR(300);
ALTER TABLE scm_token_cache     ALTER COLUMN provider TYPE VARCHAR(300);
ALTER TABLE repo_permissions    ALTER COLUMN provider TYPE VARCHAR(300);
