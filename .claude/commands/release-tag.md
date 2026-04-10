---
name: release-tag
description: Create and push a git tag after CI checks have passed, triggering the Docker publish workflow.
user-invocable: true
allowed-tools:
  - Bash
  - Read
---

# /release-tag — Tag and publish a release after checks pass.

This is phase 2 of the release process. Phase 1 (`/release`) bumped the version and pushed to main.
This command creates the git tag and pushes it, which triggers the Docker publish workflow.

Arguments passed: `$ARGUMENTS`

`$ARGUMENTS` is the version string (without `v` prefix), e.g. `1.0.0-alpha.9`.

---

## Steps

1. **Validate the argument.** If blank or not semver, stop and ask.

2. **Verify checks passed.** Run:
   ```
   gh run list --branch main --limit 4 --json name,status,conclusion
   ```
   Confirm that these checks all show `conclusion: "success"`:
   - `CI / Build & Test`
   - `CI / E2E Test`
   - `CodeQL / java-kotlin`
   - `CodeQL / actions`
   - `CVE / Dependency Check`

   If any are still in progress or failed, tell the user and stop.

3. **Verify the version matches.** Read `build.gradle` and confirm the `version` in `allprojects` matches the
   argument. If not, warn the user — they may be tagging the wrong commit.

4. **Verify the tag doesn't already exist.** Run `git tag -l v<version>`. If it exists, stop and tell the user.

5. **Create the signed annotated tag.** Attempt a signed tag first:
   ```
   git tag -s v<version> -m "Release v<version>"
   ```
   If signing fails (no GPG/SSH key configured), fall back to an unsigned annotated tag and note that signing was skipped:
   ```
   git tag -a v<version> -m "Release v<version>"
   ```

6. **Show and confirm.** Show the user:
   - `git show v<version> --stat`

   Then ask: "Ready to push the tag? This will trigger the Docker publish workflow."
   If they confirm, run `git push origin v<version>`. If they decline, remind them to push manually.
