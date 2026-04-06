# jgit-proxy — Demo Gallery

[Demo assets hosted on GitHub Releases](https://github.com/coopernetes/jgit-proxy/releases/tag/demo-assets)

---

## 1. Store-and-forward: successful push (all green)

A clean commit pushed through the `/push/` path. All validation checks pass, the push waits for review, and is forwarded
upstream on approval.

> **Note:** API approval via admin key is used here for demonstration purposes — approval is normally performed through
> the dashboard UI.

![Store-and-forward — successful push](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/push_mode_pass_email_unregistered.gif)

---

## 2. Store-and-forward: validation failures

Push rejected with multiple simultaneous failures — blocked email local-part, blocked commit message (`WIP`), diff
containing an internal hostname, and a leaked secret detected by secret scanning.

![Push — validation failures detail](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/demo-push-fix-message.gif)

---

## 3. Transparent proxy: golden-path push

A push through the `/proxy/` path. Validation passes, the push waits for review, and is forwarded upstream on approval.

> **Note:** API approval via admin key is used here for demonstration purposes — approval is normally performed through
> the dashboard UI.

![Proxy mode — golden path](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/demo-proxy-pass.gif)

---

## 4. Transparent proxy: commit message failure

Proxy mode push rejected for a blocked commit message. The error is returned synchronously in the git push response.

![Proxy mode — commit message failure](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/demo-proxy-fail-message.gif)

---

## 5. Transparent proxy: grouped validation failures

Multiple validation failures surfaced together in a single proxy mode response.

![Proxy mode — grouped validation failures](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/proxy_mode_grouped_failures.gif)

---

## 6. Transparent proxy: identity verification across providers

Identity verification running against multiple SCM providers (GitHub, GitLab, Codeberg) in a single proxy push. Shows
resolved and unresolved identity states per provider.

![Proxy mode — identity verification all providers](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/demo-proxy-identity-all.gif)

---

## 7. Store-and-forward: self-review prevention

An admin attempts to approve their own push and is blocked. The UI prevents self-approval regardless of role.

![Store-and-forward — self-review prevention](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/push_mode_prevent_self_review.gif)

---

## Screenshots

### Push records list

Dashboard overview of all push records with status badges (Forwarded, Approved, Received, Rejected) and identity
resolution indicators.

![Push records list](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_pushrecordslist.png)

---

### Push detail: validation passed, pending review

A push that passed all validation checks and is held for human review. Timeline shows: Push received → Validation passed
(10 checks) → Blocked — pending review.

![Push detail — blocked pending review](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_pushdetails_all_green_blocked.png)

---

### Push detail: diff review and approve/reject

The full push detail view showing the diff, reviewer identity, and Approve / Reject controls with an optional reason
field.

![Push detail — diff review](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_pushdetails_diff_review.png)

---

### Push detail: failed validation summary

A rejected push showing the high-level failure summary (5 validation issues) and identity resolution status.

![Push detail — failed summary](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_pushdetails_failed_summary.png)

---

### Push detail: validation step breakdown

Expanded view of individual validation step failures: blocked email local-part (`noreply`), blocked commit message
(`WIP`), diff containing an internal hostname pattern, and a leaked secret detected by secret scanning.

![Push detail — validation step details](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_pushdetails_failed_validation_details.png)

---

### Push detail: rejected — identity not linked

Push rejected because the pusher's token could not be resolved to a known proxy user. The `checkUserPermission`
validation step fails and the push is blocked with an "Identity not linked" warning.

![Push detail — identity not linked](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_pushdetail_failed_identity_not_linked.png)

---

### User list

Admin view of all registered users with email addresses, SCM identity badges, and push activity counters.

![User list](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_user_list.png)

---

### User detail

Per-user admin page showing email addresses, linked SCM identities (GitHub, GitLab), and push summary stats.

![User detail — admin view](https://github.com/coopernetes/jgit-proxy/releases/download/demo-assets/ui_admin_userdetails.png)
