---
name: release
description: Bump the project version in build.gradle and create a matching annotated git tag.
user-invocable: true
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
---

# /release — Bump the project version and create a matching git tag.

You are modifying this project's Gradle build scripts to increment the version as well as create a new git
tag to push & initiate a release process (GitHub Actions workflow).

Examples: `/release 1.0.0-alpha.3`, `/release 1.0.0-beta.1`, `/release 1.0.0`

Arguments passed: `$ARGUMENTS`

`$ARGUMENTS` is an optional semantic version string (without the `v` prefix). If omitted, the next version is
inferred automatically by incrementing the last numeric component of the current version:
- `1.0.0-alpha.9` → `1.0.0-alpha.10`
- `1.0.0-beta.2` → `1.0.0-beta.3`
- `1.0.0-rc.1` → `1.0.0-rc.2`
- `1.2.3` → `1.2.4` (patch)

---

## Steps

1. **Determine the new version.**
   - Read the current version from `build.gradle` (line containing `version = '...'` in the `allprojects` block).
   - If `$ARGUMENTS` is provided and looks like a valid semver/semver-pre string, use it as-is.
   - If `$ARGUMENTS` is blank, auto-increment: for pre-release suffixes (`-alpha.N`, `-beta.N`, `-rc.N`) increment N; otherwise increment the patch component. Show the user the inferred version and confirm before proceeding.
   - If `$ARGUMENTS` is provided but doesn't look like a valid version string, stop and ask the user to correct it.

2. **Show the current state.** Run `git tag --sort=-version:refname | head -5` and show the user the current version and the inferred/provided new version alongside the most recent tags.

3. **Update `build.gradle`.** In the `allprojects { ... }` block, replace the existing `version = '...'` line with `version = '<new-version>'`. Use the Edit tool.

4. **Run `./gradlew spotlessApply`** to ensure formatting is clean before committing.

5. **Ask about additional changes.** Run `git diff --stat` and show the output to the user, then ask: "Any other changes to include in this commit?" Wait for their response. If they say yes, apply those changes before staging. If no, proceed.

6. **Commit the version bump.** Stage `build.gradle` plus any additional files the user specified and commit:
   ```
   chore: bump version to <new-version>
   ```
   No `closes #N`, no co-author trailer needed for version bumps.

7. **Push the commit.** Run `git push` to push the version bump commit to main. This triggers CI, CodeQL, and CVE
   workflows on the new commit SHA.

8. **Wait for checks.** Tell the user:

   > Version bump pushed to main. CI, CodeQL, and CVE checks are now running on this commit.
   >
   > **When all checks are green**, run `/release-tag <new-version>` to create and push the tag.
   >
   > Monitor check status: `gh run list --branch main --limit 4`

   **Stop here.** Do NOT create a tag or push tags. The tag ruleset on GitHub will reject the tag push
   if the required status checks haven't passed yet.
