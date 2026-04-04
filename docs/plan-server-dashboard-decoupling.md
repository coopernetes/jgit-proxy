# Server/Dashboard Decoupling Plan

## Current State

### Can `jgit-proxy-server` run standalone?

Technically yes — it has no compile-time dependency on `jgit-proxy-dashboard` and brings its own DB
backends. However, **store-and-forward mode is functionally broken without a dashboard**.

In `GitProxyServletRegistrar.registerGitServlet()`, the `UiApprovalGateway` is hardwired:

```java
var approvalGateway = new UiApprovalGateway(pushStore);
```

`UiApprovalGateway` polls the `PushStore` every 5 seconds waiting for a human to flip the status to
`APPROVED` via the REST API — which lives only in `jgit-proxy-dashboard`. The hook chain in
`StoreAndForwardReceivePackFactory` ends with `ApprovalPreReceiveHook`. Every push lands in `BLOCKED`
status and will hang the git client for 30 minutes before timing out.

**Proxy mode (`/proxy/`) is unaffected.** `AllowApprovedPushFilter` simply never pre-approves anything
(harmless), and the full validation filter chain runs correctly.

---

## What Is Hard-Coded

### 1. `ApprovalGateway` selection — the critical issue

`GitProxyServletRegistrar` constructs `UiApprovalGateway` directly, despite `ApprovalGateway` already
being an interface with multiple implementations. There is no injection point.

### 2. The store-and-forward hook chain

`StoreAndForwardReceivePackFactory` compiles a fixed array of 14 pre-receive hooks inline. It already
has a `persistenceHook != null` branch that conditionally elides persistence/approval hooks, but there
are no other extension points.

### 3. The proxy filter chain

`GitProxyServletRegistrar.registerFilters()` registers ~18 filters with hardcoded `new` calls.
`AllowApprovedPushFilter` is a dashboard concern registered here but harmless when no approval is ever
granted. There is no mechanism to add or remove filters per deployment type.

---

## Proposed Plan

### Phase 1 — Fix standalone server (minimal, high ROI)

**Goal:** make `/push/` store-and-forward work correctly without a dashboard, with no approval polling.

1. Add `AutoApprovalGateway` to `jgit-proxy-core` — auto-approves clean pushes instantly, and
   auto-rejects violated pushes immediately (no polling, no dashboard required).

2. Add `approval-mode` to YAML config (`auto` | `ui` | `servicenow`). Default `auto` for the
   server-only entry point, `ui` when running with the dashboard.

3. Add `JettyConfigurationBuilder.buildApprovalGateway(PushStore)` to instantiate the correct gateway
   from config.

4. Thread `ApprovalGateway` through `registerGitServlet(...)` as an explicit parameter — remove the
   hardwired `new UiApprovalGateway(pushStore)` from inside the registrar.

5. In `GitProxyWithDashboardApplication`, always construct and pass a `UiApprovalGateway` regardless of
   config (it owns the REST API needed to drive it).

The `ApprovalGateway` interface already models this correctly — the only missing piece is plumbing it
through the call chain.

---

### Phase 2 — Decouple filter and hook chains (medium effort)

**Goal:** make dashboard-specific concerns opt-in rather than always registered.

#### Proxy filter chain

`AllowApprovedPushFilter` is a dashboard concern that is unconditionally registered by the server-level
registrar. Two options:

- **Option A (simpler):** Split `registerFilters` into `registerCoreFilters()` (always runs) and
  `registerApprovalFilters()` (dashboard calls additionally). This mirrors the existing
  `persistenceHook != null` conditional in the hook chain.

- **Option B (more flexible):** Introduce a `FilterChainCustomizer` callback interface that
  `registerFilters` accepts. `GitProxyWithDashboardApplication` injects `AllowApprovedPushFilter`; the
  standalone server passes an empty list.

Option A is preferred for its simplicity unless a third deployment type needs a different combination.

#### Store-and-forward hook chain

`StoreAndForwardReceivePackFactory` already handles the `pushStore == null` case cleanly. No immediate
change needed unless a real use case for custom hooks emerges. Defer further abstraction.

---

## Summary of Changes by Module

| Module                 | Change                                                                                                                                                             |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `jgit-proxy-core`      | Add `AutoApprovalGateway`                                                                                                                                          |
| `jgit-proxy-server`    | Add `approval-mode` config key; `buildApprovalGateway()`; add `ApprovalGateway` param to `registerGitServlet`; split `registerFilters` into core + approval halves |
| `jgit-proxy-dashboard` | Pass `UiApprovalGateway` explicitly; call `registerApprovalFilters()` in addition to core filters                                                                  |

---

## Non-Goals

- Full plugin SPI for hooks/filters — not warranted yet.
- Removing `PushStore` from standalone — it is already useful for audit logging and the `memory` backend
  requires no infrastructure.
- Changing the transparent proxy path — it works correctly standalone today.
