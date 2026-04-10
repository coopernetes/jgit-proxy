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

`$ARGUMENTS` is a semantic version string (without the `v` prefix) that will be set as the new version in `build.gradle`
and used for the git tag. It should follow semver or semver-pre format, e.g. `1.0.0`, `1.0.0-alpha.3`, `1.0.0-beta.1`.

---

## Steps

1. **Validate the argument.** The argument is the new version string (no `v` prefix). If it's blank or doesn't look like a semver/semver-pre string, stop and ask the user to provide one.

2. **Show the current state.** Run `git tag --sort=-version:refname | head -5` and read `build.gradle` to show the user the current version (line containing `version = '...'` in the `allprojects` block) and the most recent tags.

3. **Update `build.gradle`.** In the `allprojects { ... }` block, replace the existing `version = '...'` line with `version = '<new-version>'`. Use the Edit tool.

4. **Run `./gradlew spotlessApply`** to ensure formatting is clean before committing.

5. **Ask about additional changes.** Before committing, ask the user: "Any other changes to include in this commit?" Wait for their response. If they say yes, apply those changes before staging. If no, proceed.

6. **Commit the version bump.** Stage `build.gradle` plus any additional files the user specified and commit:
   ```
   chore: bump version to <new-version>
   ```
   No `closes #N`, no co-author trailer needed for version bumps.

7. **Create the annotated tag.**
   ```
   git tag -a v<new-version> -m "Release v<new-version>"
   ```

8. **Confirm.** Show the user:
   - The new version in `build.gradle`
   - The tag just created (`git show v<new-version> --stat`)
   - A reminder: `git push && git push --tags` to publish (don't push automatically)
