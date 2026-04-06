-- Initial schema for jgit-proxy.
-- Compatible with H2, PostgreSQL, and SQLite.

CREATE TABLE IF NOT EXISTS push_records (
    id              VARCHAR(36) PRIMARY KEY,
    timestamp       TIMESTAMP NOT NULL,
    url             VARCHAR(1024),
    upstream_url    VARCHAR(1024),
    project         VARCHAR(255),
    repo_name       VARCHAR(255),
    branch          VARCHAR(512),
    commit_from     VARCHAR(40),
    commit_to       VARCHAR(40),
    message         TEXT,
    author          VARCHAR(255),
    author_email    VARCHAR(255),
    committer       VARCHAR(255),
    committer_email VARCHAR(255),
    push_user       VARCHAR(255),
    resolved_user   VARCHAR(255),
    user_email      VARCHAR(255),
    method          VARCHAR(10),
    status          VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    error_message   TEXT,
    blocked_message TEXT,
    auto_approved   BOOLEAN NOT NULL DEFAULT FALSE,
    auto_rejected   BOOLEAN NOT NULL DEFAULT FALSE,
    scm_username    VARCHAR(255),
    forwarded_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS push_steps (
    id              VARCHAR(36) PRIMARY KEY,
    push_id         VARCHAR(36) NOT NULL REFERENCES push_records(id) ON DELETE CASCADE,
    step_name       VARCHAR(255) NOT NULL,
    step_order      INT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PASS',
    content         TEXT,
    error_message   TEXT,
    blocked_message TEXT,
    logs            TEXT,
    timestamp       TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS push_commits (
    push_id         VARCHAR(36) NOT NULL REFERENCES push_records(id) ON DELETE CASCADE,
    sha             VARCHAR(40) NOT NULL,
    parent_sha      VARCHAR(40),
    author_name     VARCHAR(255),
    author_email    VARCHAR(255),
    committer_name  VARCHAR(255),
    committer_email VARCHAR(255),
    message         TEXT,
    commit_date     TIMESTAMP,
    signature       TEXT,
    signed_off_by   TEXT,
    PRIMARY KEY (push_id, sha)
);

CREATE TABLE IF NOT EXISTS push_attestations (
    push_id             VARCHAR(36) NOT NULL REFERENCES push_records(id) ON DELETE CASCADE,
    type                VARCHAR(20) NOT NULL,
    reviewer_username   VARCHAR(255),
    reviewer_email      VARCHAR(255),
    reason              TEXT,
    automated           BOOLEAN NOT NULL DEFAULT FALSE,
    timestamp           TIMESTAMP NOT NULL,
    PRIMARY KEY (push_id, type, timestamp)
);

-- ---------------------------------------------------------------------------
-- User accounts
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS proxy_users (
    username      VARCHAR(255) PRIMARY KEY,
    password_hash VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS user_emails (
    username    VARCHAR(255) NOT NULL REFERENCES proxy_users(username) ON DELETE CASCADE,
    email       VARCHAR(255) NOT NULL,
    verified    BOOLEAN      NOT NULL DEFAULT FALSE,
    auth_source VARCHAR(20)  NOT NULL DEFAULT 'local',
    locked      BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (username, email)
);

CREATE TABLE IF NOT EXISTS user_scm_identities (
    username         VARCHAR(255) NOT NULL REFERENCES proxy_users(username) ON DELETE CASCADE,
    provider         VARCHAR(100) NOT NULL,
    scm_username     VARCHAR(255) NOT NULL,
    verified         BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (username, provider, scm_username)
);

CREATE INDEX IF NOT EXISTS idx_user_emails_email ON user_emails(email);

-- ---------------------------------------------------------------------------
-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_push_records_status ON push_records(status);
CREATE INDEX IF NOT EXISTS idx_push_records_project ON push_records(project);
CREATE INDEX IF NOT EXISTS idx_push_records_repo ON push_records(repo_name);
CREATE INDEX IF NOT EXISTS idx_push_records_user ON push_records(push_user);
CREATE INDEX IF NOT EXISTS idx_push_records_timestamp ON push_records(timestamp);
CREATE INDEX IF NOT EXISTS idx_push_steps_push_id ON push_steps(push_id);
CREATE INDEX IF NOT EXISTS idx_push_commits_push_id ON push_commits(push_id);
CREATE INDEX IF NOT EXISTS idx_push_records_commit_to ON push_records(commit_to, branch, repo_name);
