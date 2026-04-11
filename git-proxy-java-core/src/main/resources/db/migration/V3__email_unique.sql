-- Enforce global email uniqueness across all users.
-- Mirrors the UNIQUE(provider, scm_username) constraint already on user_scm_identities.
ALTER TABLE user_emails ADD CONSTRAINT uq_user_emails_email UNIQUE (email);
