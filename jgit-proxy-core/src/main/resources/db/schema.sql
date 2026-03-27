-- Schema for push audit records.
-- Compatible with H2, PostgreSQL, and SQLite (with minor type mapping handled by JdbcPushStore).

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
    author          VARCHAR(255),
    author_email    VARCHAR(255),
    push_user       VARCHAR(255),
    method          VARCHAR(10),
    status          VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    error_message   TEXT,
    blocked_message TEXT,
    auto_approved   BOOLEAN NOT NULL DEFAULT FALSE,
    auto_rejected   BOOLEAN NOT NULL DEFAULT FALSE
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

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_push_records_status ON push_records(status);
CREATE INDEX IF NOT EXISTS idx_push_records_project ON push_records(project);
CREATE INDEX IF NOT EXISTS idx_push_records_repo ON push_records(repo_name);
CREATE INDEX IF NOT EXISTS idx_push_records_user ON push_records(push_user);
CREATE INDEX IF NOT EXISTS idx_push_records_timestamp ON push_records(timestamp);
CREATE INDEX IF NOT EXISTS idx_push_steps_push_id ON push_steps(push_id);
CREATE INDEX IF NOT EXISTS idx_push_commits_push_id ON push_commits(push_id);
