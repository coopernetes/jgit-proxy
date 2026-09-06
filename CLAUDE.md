# fogwall — Claude context

## Positioning

fogwall is not "a Java rewrite of git-proxy that solves an OSPO-approval problem." Think of it as a general-purpose
**gateway/integration layer for an enterprise's software estate**, with git push validation as the first fully-built use
case, not the ceiling. Design decisions should keep the door open for:

- **SDLC/SCM control plane** — a single policy-enforcement chokepoint sitting in front of heterogeneous SCM platforms
  (GitHub, GitLab, Bitbucket, Forgejo/Gitea, and eventually non-git systems), so a regulated org doesn't need bespoke
  compliance tooling bolted onto each one individually.
- **M&A / subsidiary integration gateway** — a way to bridge two orgs' disparate SCM estates during an acquisition or
  integration without granting direct cross-boundary network access, while still enforcing each side's policy.
- **Inner-source enablement** — the trust/approval/audit layer that lets a regulated org run an internal
  open-source-style contribution model without each app team reinventing review and provenance controls.

When evaluating a new feature, prefer the more general abstraction (provider-agnostic, protocol-agnostic where
reasonable) over one that only serves the git-push case, even if git push is what's shipping today.

## Design principles

fogwall sits at a security boundary. When a design choice pits security against convenience, security wins — but treat
that as a rare, real tradeoff to name explicitly, not a reflex; a control developers route around because it's unusable
isn't actually providing security.

- **Security is non-negotiable.** Never weaken the correctness of a validation or approval control for the sake of
  ergonomics. Where a feature must pick between "safe by default" and "convenient by default," default to safe and make
  the convenient path an explicit, visible opt-in (self-certify grants, admin override, auto-approve mode) — never a
  silent default.
- **Auditability and transparency are part of the security model, not a nice-to-have.** Every decision fogwall makes
  (blocked, approved, forwarded, overridden) should be explainable after the fact — who, which rule, what evidence — not
  just enforced in the moment. A feature that can't produce an audit trail for its own decisions isn't done.
- **Don't let roadmap ambition become shipped-system complexity.** The gateway/integration-layer vision above is a north
  star, not a mandate to wire every backlog item into one interdependent system. Prefer features that are individually
  optional and composable — an org should be able to run only the pieces it needs — over a design where understanding or
  operating one feature requires understanding all of them. If a new capability would raise the baseline complexity for
  someone not using it, that's a signal to make it opt-in or a separate module rather than folding it into the core
  path.
- **fogwall sits inline on every push at large-enterprise scale — the default path must stay cheap.** A validation step
  that's merely "a bit slow" in isolation becomes a real latency and throughput problem multiplied across an org's whole
  clone/push/fetch volume. Expensive work (deep diff scanning, external API calls, full-history inspection) is opt-in,
  not opt-out: gate it behind explicit config, default it off, and where practical make it size/scope-bounded so an
  operator who opts in can still bound the cost. When proposing a new validation feature, ask what it costs per push at
  high volume before asking whether it's a good idea.
- **Parity across peer technologies is a feature requirement, not a nice-to-have.** fogwall is general-purpose even when
  a given deployment only needs one driver. When a capability lands, it lands across the whole axis it sits on — or the
  gap is named in a tracked issue at ship time, never just a code comment ("JDBC-only for now" in a comment is how gaps
  get lost). Genuine exceptions exist (an upstream API that simply lacks the capability, a protocol difference that
  makes a feature impossible in one mode) — those are fine, but they are documented and deliberate, not the silent
  residue of implementing against whichever driver was convenient that day. The parity axes:
  - **both proxy modes** — transparent proxy and server mode (formerly store-and-forward), including both of server
    mode's transports (HTTP and SSH)
  - **both database families** — JDBC and MongoDB get equivalent store implementations; and within JDBC, the SQL
    derivatives (H2, Postgres, MySQL, MariaDB) behave consistently
  - **all providers** — GitHub, GitLab, Forgejo/Gitea/Codeberg, Bitbucket do the same thing wherever possible and
    reasonable

- **Prefer git primitives over provider APIs.** For commit, ref, reachability, and content questions, ask the local
  mirror through JGit. Provider REST APIs answer a looser question (fork-shared object storage, for one) and are
  reserved for what git cannot answer: identity resolution, key listing, repo visibility. If the mirror is wrong, fix
  its accuracy rather than swapping oracles.
- **fogwall is not in the encryption/KMS business.** Credential-at-rest features use stdlib primitives correctly
  (AES-GCM, IV/AAD handling, hard delete) behind a thin key-custody SPI. KMS integration and node-root threat models are
  platform concerns.

## Repository layout

| Module              | Purpose                                                                                                     |
| ------------------- | ----------------------------------------------------------------------------------------------------------- |
| `fogwall-core`      | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction                  |
| `fogwall-server`    | Standalone proxy-only server (`FogwallJettyApplication`) — no dashboard, no Spring                          |
| `fogwall-dashboard` | Dashboard + REST API (`FogwallDashboardApplication`) — Spring MVC, approval UI, depends on `fogwall-server` |

## Architecture

Two proxy modes, both configurable per-provider:

- **Server mode** (`/server/<provider>/<owner>/<repo>.git`; formerly "store-and-forward", still served at the deprecated
  `/push/…` alias) — JGit ReceivePack receives the push locally, runs a pre-receive chain of validation hooks, then
  forwards upstream using the client's credentials. `ServerReceivePackFactory` assembles the current hook roster.
- **Transparent proxy** (`/proxy/<provider>/<owner>/<repo>.git`) — Jetty's `ProxyServlet` forwards the request; a
  servlet filter chain parses and inspects the pack data before it reaches the upstream. `FogwallServletRegistrar`
  assembles the current filter chain.

The main behavioural difference between the modes is streaming: server mode can send progress to the client live via
JGit hooks, while the transparent proxy must buffer everything and send one response at the end of the filter chain (see
the streaming constraint below).

Server mode also has an SSH transport (`fogwall-server`'s MINA SSHD-based `SshGitServer` / `SshGitReceiveCommand` /
`SshGitUploadCommand`) alongside the HTTP one — it's the same mode, delegating to the same `ServerReceivePackFactory`
hook chain, just reached over `git-receive-pack`/`git-upload-pack` SSH commands instead of HTTP, with upstream auth via
the client's forwarded SSH agent. Not a third proxy mode; a second transport for the same one.

## Client output — streaming constraint

**Server mode** uses JGit `ReceivePack` pre-receive hooks. Each hook can call `rp.sendMessage()` at any point and the
message streams to the git client immediately as a sideband progress packet (`remote: …`). This is how per-step progress
lines are sent live.

**Transparent proxy** uses servlet filters. The HTTP response is a single buffered reply — there is no mechanism to
stream partial output mid-filter-chain. Validation filters must _accumulate_ their result and return;
`ValidationSummaryFilter` and `PushFinalizerFilter` collect everything and write one response at the end using
`sendGitError`.

## Lineage

fogwall's push-validation core traces back to [finos/git-proxy](https://github.com/finos/git-proxy) — the Node.js
original designed the Action/Step model, Sink interface, approval lifecycle, and multi-provider architecture that
fogwall's own abstractions are informed by. Refer to it for prior art when porting or extending that specific piece of
the system. It is a reference point, not a spec fogwall is obligated to mirror going forward — fogwall's roadmap
(gateway/integration-layer use cases above) extends past what git-proxy set out to do.

## Development

Detailed build, test, run, and Docker Compose instructions live in [CONTRIBUTING.md](CONTRIBUTING.md) — treat it as the
source of truth for exact commands, since it's written for human contributors and kept current. In short:

- `./gradlew spotlessApply && ./gradlew build` — format then compile + unit test
- `./gradlew e2eTest` — e2e tests (requires Docker/Podman)
- `bash compose.sh -- up -d` — local stack (fogwall + Gitea); see CONTRIBUTING.md for auth/db overlay flags
- Locally, run targeted unit tests plus a compile; leave coverage gates and e2e to CI — it is the arbiter and runs
  without cache.
- When a build or test fails or hangs, run it unfiltered into a file and grep the file. Never pipe through `grep FAILED`
  / `tail` — a hung test never writes `build/test-results`, so the console stream is the only record.
- A feature isn't done until it has been pushed or fetched through the running proxy end to end. Unit tests plus a clean
  compile have hidden unwired filter chains before.

## Git workflow

Mechanical rules (`git add -A`, `--no-verify`, `[ci skip]`, `Claude-Session:` trailers, `git merge main`,
`git rebase -i`, squash/rebase merges, label creation, external-repo issues, generated-with footers) are enforced by
`.claude/hooks/guard-bash.sh`. When it blocks a command, do what the message says; never work around it.

- Always start a new feature branch from an up-to-date `origin/main` — `git fetch origin main` first, branch from
  `origin/main`, not a possibly-stale local `main`.
- Always squash related commits into one before pushing, with `git reset --soft` against a freshly fetched `origin/main`
  — never the local `main` ref, which goes stale in multi-worktree setups. Exception: never squash across commits that
  differ by author or model trailer; that multi-commit structure is the provenance record.
- A branch that falls behind is rebased, locally, and force-pushed; the `sync-pr-branch` skill has the full sequence
  including re-arming auto-merge. Nothing goes through the GitHub UI's "Update branch" or web editor — commits must
  carry the developer's signature, not GitHub's key.
- Stage paths explicitly. The working tree may hold sensitive or scratch files.
- Commit messages and PR bodies in plain language: lead with what a developer saw go wrong, then the cause, then the
  fix. Dense graph shorthand is not documentation.
- **Keep them terse.** A commit message earns length only when no issue already describes the change in detail — when
  one does, say what changed and link it. Same for PR bodies: short, and never claiming more than the code does.
- Always include a `Co-Authored-By` trailer crediting the Claude model that did the work (e.g.
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` — use the current model's name, not this example, if it
  differs). This is a project transparency requirement. No other Claude trailers.
- Always include `closes #N` / `resolves #N` when addressing a GitHub issue — but grep for the actual implementation
  first. A docs-only PR once closed #83 and an unrelated commit closed #107.
- `gh pr merge` is always `--merge`.

## Issue and PR hygiene

This is a public repository.

- Update an issue or PR body when state changes; don't litter threads with progress comments. Only the latest state
  matters, and edit history is there if anyone ever needs it.
- Only use labels that already exist; never create new ones. Don't prefix an issue title with a word that is already
  applied as a label.
- Say "point N", not "#N", for numbered sub-items inside an issue — GitHub linkifies `#N`.
- An issue states what is wanted, not how it is built. Code in an issue is a rough draft or a pointer to where the real
  thing will live — never a transcribed implementation, which is stale the moment one lands.
- Detailed, not exhaustive. An issue sketches what will be implemented and names the boundaries; it does not argue the
  case, rehearse alternatives that were rejected, or explain the same point twice. If a section could be cut without
  changing what gets built, cut it.
- No narrative anywhere in the shipped artifact. Commit messages, PR bodies, issues and comments describe what is there,
  not the path taken to it — a comment recording what was tried and abandoned on a feature branch is noise once the
  branch merges.
- Anything that belongs upstream (finos/git-proxy, JGit, Jetty…) is noted for the maintainer to file by hand.

## Backwards compatibility

Past the 1.0.0 line (current version well past it — see `build.gradle`) — respect backcompat, don't break freely:

- **Config keys** — don't rename/remove without a deprecation path; accept old and new for at least one minor release.
- **SQL schema** — changes go through `DatabaseMigrator` (new migration file + registry entry); never edit an applied
  migration.
- **Mongo collections** — don't rename once shipped; a rename needs a migration step (copy + drop), documented.
- **REST API shapes** — additive only, no breaking field removals.
- Java APIs inside `fogwall-core` are still internal and can break between minors until a stable embedding story is
  declared.

Before renaming a config key, table, column, or collection: pause and ask — the answer is almost always "ship a
migration instead."

## Configuration

Refer to [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for detailed docs on YAML config structure, environment variable
overrides, and provider-specific settings.

## Documentation upkeep

When a PR introduces or materially changes a user-facing feature, check whether it needs a docs update as part of that
PR — don't let doc drift accumulate to be reconciled later in a big batch:

- [docs/USER_GUIDE.md](docs/USER_GUIDE.md) — anything a developer pushing through the proxy would need to know
- [docs/ADMIN_GUIDE.md](docs/ADMIN_GUIDE.md) — anything an operator configuring/running fogwall would need to know
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md) — any new or changed config key
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — any new abstraction, pipeline step, or design rationale worth
  explaining for contributors

Not every change needs all four — use judgment — but check rather than skip the check.

## Where the rest lives

This file is loaded into every session, so it holds only what every session needs. Narrower guidance is loaded on
demand:

- `.claude/rules/` — path-scoped conventions that load when matching files are touched: `java.md` (DI, comments),
  `config.md` (YAML and `*Settings` rules), `testing.md`, `build-ci.md` (dependency pins, workflows, Docker).
- `.claude/skills/` and `.claude/commands/` — multi-step procedures (`sync-pr-branch`, `pin-transitive-cve`,
  `refresh-pattern-bundles`, releases, action pins). Prefer invoking one over reconstructing the steps.
- `.claude/hooks/guard-bash.sh` — the enforced prohibitions listed under Git workflow.

## Roadmap & architecture

There are gists linked in the root README. Only look up these details as necessary for planning refactors or
understanding design rationale. The code itself is the source of truth for how the system works ultimately.
