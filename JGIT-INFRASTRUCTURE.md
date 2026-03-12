# JGit-Based Repository Inspection Infrastructure

This document describes the JGit-based infrastructure added to enable filter functionality similar to the Node.js git-proxy project.

## Overview

The infrastructure uses JGit to clone and inspect remote repositories locally, enabling filters to:
- Extract complete commit information (not just the head commit)
- Analyze commit ranges and diffs
- Validate GPG signatures
- Scan for secrets and sensitive information
- Check commit messages and author emails

## Components

### LocalRepositoryCache

Manages local bare clones of remote repositories:

```java
// Initialize cache (typically done once at application startup)
LocalRepositoryCache cache = new LocalRepositoryCache();

// Get or clone a repository
Repository repo = cache.getOrClone("https://github.com/owner/repo.git");

// Use JGit operations on the repository
try (Git git = new Git(repo)) {
    // ... perform git operations
}
```

**Features:**
- Caches repositories in temporary directories
- Automatically fetches updates when accessing cached repos
- Cleans up on JVM shutdown
- Thread-safe concurrent access

### CommitInspectionService

Provides utilities for extracting commit information:

```java
// Get details for a specific commit
Commit commit = CommitInspectionService.getCommitDetails(repository, "abc123");

// Get all commits in a range
List<Commit> commits = CommitInspectionService.getCommitRange(
    repository, 
    "oldCommit", 
    "newCommit"
);

// Get diff between commits
List<DiffEntry> diff = CommitInspectionService.getDiff(
    repository,
    "oldCommit", 
    "newCommit"
);

// Get formatted diff as string
String diffText = CommitInspectionService.getFormattedDiff(
    repository,
    "oldCommit",
    "newCommit"
);
```

### EnrichPushCommitsFilter

A servlet filter that enriches push requests with full commit information:

```java
// Registered in filter chain after ParseGitRequestFilter
var enrichFilter = new EnrichPushCommitsFilter(provider, repositoryCache);
context.addFilter(enrichFilterHolder, urlPattern, EnumSet.of(DispatcherType.REQUEST));
```

**What it does:**
1. Extracts basic commit info from the push packet
2. Clones/fetches the remote repository locally
3. Uses JGit to extract all commits in the push range
4. Populates `GitRequestDetails.commits` with full commit information
5. Extracts user email from commit author

### TemporaryRepositoryResolver

Integrates with LocalRepositoryCache to serve repositories for JGit operations:

```java
var resolver = new TemporaryRepositoryResolver(cache);
// Used by JGit servlet handlers to resolve repository requests
```

## Usage Example

Here's how the infrastructure works in a typical push operation:

1. **Git client pushes to proxy**:
   ```
   git push http://proxy:8080/github.com/owner/repo.git
   ```

2. **ForceGitClientFilter** validates the client

3. **ParseGitRequestFilter** parses the basic push information from the packet

4. **EnrichPushCommitsFilter** (NEW):
   - Clones/fetches `https://github.com/owner/repo.git` to temp directory
   - Uses JGit to extract all commits in the range
   - Populates full commit details in `GitRequestDetails`

5. **Validation Filters** can now access complete commit information:
   ```java
   var commits = requestDetails.getCommits(); // All commits in push
   for (Commit commit : commits) {
       String email = commit.getAuthor().getEmail();
       String message = commit.getMessage();
       String signature = commit.getSignature();
       // ... validate
   }
   ```

6. **Proxy completes** if all filters pass

## Filter Examples

### CheckAuthorEmailsFilter

Validates commit author emails against configured patterns:

```java
var commitConfig = CommitConfig.builder()
    .author(AuthorConfig.builder()
        .email(EmailConfig.builder()
            .domain(DomainConfig.builder()
                .allow(".*\\.company\\.com$")
                .build())
            .build())
        .build())
    .build();

var filter = new CheckAuthorEmailsFilter(commitConfig);
```

### SecretScanningFilter

Scans commits for potential secrets:

```java
var secretConfig = SecretScanningConfig.defaultConfig(); // Includes common patterns
var filter = new SecretScanningFilter(secretConfig);
```

### GpgSignatureFilter

Validates GPG signatures on commits:

```java
var gpgConfig = GpgConfig.builder()
    .enabled(true)
    .requireSignedCommits(true)
    .trustedKeysFile("/path/to/public-keys.asc")
    .build();

var filter = new GpgSignatureFilter(gpgConfig);
```

## Performance Considerations

- **First Push**: Clones repository (slower)
- **Subsequent Pushes**: Uses cached clone with fetch (faster)
- **Memory**: Bare repositories are compact (no working directory)
- **Disk**: Cached in temp directory, cleaned up on shutdown
- **Concurrency**: Thread-safe cache with synchronized cloning

## Comparison with Node.js git-proxy

| Feature | Node.js git-proxy | Java jgit-proxy |
|---------|-------------------|-----------------|
| Repository Cloning | Child process `git clone` | JGit API |
| Commit Inspection | Child process `git log`, `git show` | JGit RevWalk |
| Diff Analysis | Child process `git diff` | JGit DiffFormatter |
| GPG Verification | Child process `git verify-commit` | BouncyCastle PGP |
| Pack Analysis | Child process `git verify-pack` | Not yet implemented |

## Future Enhancements

1. **Pack File Analysis**: Implement hidden commits check using JGit pack file APIs
2. **Diff Content Scanning**: Extend SecretScanningFilter to scan actual file diffs
3. **Repository Retention**: Add configurable cache expiry and size limits
4. **Async Cloning**: Clone repositories asynchronously to avoid blocking requests
5. **Mirror Mode**: Support local git mirrors instead of on-demand cloning
