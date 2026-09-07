-- SCM API proxy: schema for the whole feature, grouped into one migration since none of it has shipped yet
-- (no production database has ever run these tables — feel free to keep grouping future pre-release changes here
-- rather than adding new versions, per project convention: migrations only need to be immutable once released).

-- Opaque GraphQL node ID -> owner/repo resolution cache. GraphQL mutations (GitHub) reference their target only by
-- an opaque node ID, never by owner/repo, so the SCM API proxy must resolve one before it can run the permission
-- check. The TTL (enforced on read via cached_at, same pattern as scm_token_cache/ssh_fingerprint_cache) is a
-- security parameter, not just a perf knob — see docs/internals/SCM_API_PROXY.md §3c: a node ID can outlive a repo
-- rename/transfer while the owner/repo it resolves to changes underneath it.
--
-- GitHub's own table: each dialect that resolves an opaque identifier keeps its own, rather than sharing one column
-- that would have to be named for whichever API got there first. GitLab's equivalent is
-- scm_api_gitlab_project_cache below.
CREATE TABLE IF NOT EXISTS scm_api_github_node_cache (
    provider    VARCHAR(100) NOT NULL,
    node_id     VARCHAR(255) NOT NULL,
    repo_owner  VARCHAR(255) NOT NULL,
    repo_name   VARCHAR(255) NOT NULL,
    cached_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (provider, node_id)
);

-- GitLab numeric project ID -> owner/repo resolution cache. Kept separate from scm_api_github_node_cache on purpose:
-- "node ID" is GitHub GraphQL vocabulary, and a GitLab project ID is a different identifier from a different API,
-- so sharing one column would make the schema describe something that does not exist. Same TTL semantics and the
-- same security reasoning: an ID outlives a rename or transfer while what it resolves to changes underneath it.
--
-- Needed because `glab mr create` addresses the SOURCE project in the URL and carries the upstream only as a numeric
-- target_project_id in the request body, so authorizing on the URL alone would check the fork rather than the
-- upstream the merge request is opened on. See docs/internals/SCM_API_PROXY.md.
CREATE TABLE IF NOT EXISTS scm_api_gitlab_project_cache (
    provider    VARCHAR(100) NOT NULL,
    project_id  VARCHAR(255) NOT NULL,
    repo_owner  VARCHAR(255) NOT NULL,
    repo_name   VARCHAR(255) NOT NULL,
    cached_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (provider, project_id)
);

-- Audit trail: one record per proxied mutation. Same auditability bar as push_records — who, which rule, what
-- target, what evidence. Write-once: a mutation's allowlist/permission decision and forward outcome are all known
-- synchronously, so unlike push_records there is no update path.
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
    variables_json  TEXT,
    user_agent      VARCHAR(512),
    client_type     VARCHAR(32),
    -- Version parsed out of user_agent, best effort; null when the header carried none fogwall could
    -- read. Its own column because a CLI wire-format break is version-specific, and "which releases"
    -- is a query rather than a scan of the raw header, which is kept regardless.
    client_version  VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_scm_api_action_records_resolved_user ON scm_api_action_records (resolved_user);
CREATE INDEX IF NOT EXISTS idx_scm_api_action_records_timestamp ON scm_api_action_records (timestamp);
