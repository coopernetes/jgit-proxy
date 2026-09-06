-- Identical to db/migration/V15__scm_api_proxy.sql except for two MySQL/MariaDB dialect differences:
--   * standalone CREATE INDEX statements drop IF NOT EXISTS (unsupported by MySQL; not needed here since this
--     migration only ever runs once per version);
--   * variables_json is MEDIUMTEXT rather than TEXT -- see the column comment below.
CREATE TABLE IF NOT EXISTS scm_api_github_node_cache (
    provider    VARCHAR(100) NOT NULL,
    node_id     VARCHAR(255) NOT NULL,
    repo_owner  VARCHAR(255) NOT NULL,
    repo_name   VARCHAR(255) NOT NULL,
    cached_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (provider, node_id)
);

CREATE TABLE IF NOT EXISTS scm_api_gitlab_project_cache (
    provider    VARCHAR(100) NOT NULL,
    project_id  VARCHAR(255) NOT NULL,
    repo_owner  VARCHAR(255) NOT NULL,
    repo_name   VARCHAR(255) NOT NULL,
    cached_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (provider, project_id)
);

CREATE TABLE IF NOT EXISTS scm_api_action_records (
    id              VARCHAR(36)  PRIMARY KEY,
    timestamp       TIMESTAMP    NOT NULL,
    provider        VARCHAR(100) NOT NULL,
    scm_username    VARCHAR(255),
    resolved_user   VARCHAR(255),
    repo_owner      VARCHAR(255),
    repo_name       VARCHAR(255),
    -- Null when fogwall refused the request before it could name the operation: an endpoint matching no
    -- allowlist rule has no operation to record, and the refusal is still worth keeping. The reason column
    -- carries the method and path in that case.
    mutation_field  VARCHAR(100),
    node_id         VARCHAR(255),
    node_type       VARCHAR(30),
    status          VARCHAR(20)  NOT NULL,
    reason          TEXT,
    -- MEDIUMTEXT, not TEXT: MySQL bounds TEXT at 64 KiB while H2 and Postgres leave it unbounded, and a request
    -- body is accepted up to 4 MiB. A GitHub mutation carrying variables larger than 64 KiB would overflow the
    -- column, and the audit write is wrapped in a catch -- so the row for a mutation that already reached the
    -- upstream would vanish here and be written everywhere else. reason needs no widening: it is capped in
    -- ScmApiAuditFilter well inside 64 KiB.
    variables_json  MEDIUMTEXT,
    user_agent      VARCHAR(512),
    client_type     VARCHAR(32),
    -- Version parsed out of user_agent, best effort; null when the header carried none fogwall could
    -- read. Its own column because a CLI wire-format break is version-specific, and "which releases"
    -- is a query rather than a scan of the raw header, which is kept regardless.
    client_version  VARCHAR(64)
);

CREATE INDEX idx_scm_api_action_records_resolved_user ON scm_api_action_records (resolved_user);
CREATE INDEX idx_scm_api_action_records_timestamp ON scm_api_action_records (timestamp);
