package org.finos.gitproxy.jetty.config;

import lombok.Data;

/** Binds the {@code reload:} block in git-proxy.yml. */
@Data
public class ReloadConfig {

    private FileSourceConfig file = new FileSourceConfig();
    private GitSourceConfig git = new GitSourceConfig();

    /** Configuration for reloading from a local filesystem path. */
    @Data
    public static class FileSourceConfig {

        /**
         * When {@code true}, the config file at {@link #path} is watched for changes. On modification the proxy reloads
         * all hot-reloadable configuration (commit rules, auth settings) without restarting.
         */
        private boolean enabled = false;

        /**
         * Absolute or relative path to an override YAML file to watch. Relative paths are resolved from the working
         * directory. This file is overlaid on top of the classpath base config, profile configs, and env var overrides,
         * so it takes the highest priority (same as mounting a file into the container).
         *
         * <p>Example: {@code /app/conf/git-proxy-local.yml} or {@code ./git-proxy-local.yml}
         */
        private String path = "";
    }

    /** Configuration for reloading config from a git repository. */
    @Data
    public static class GitSourceConfig {

        /**
         * When {@code true}, the proxy periodically clones or pulls the configured git repository and reloads
         * hot-reloadable configuration from {@link #filePath} within that repo.
         */
        private boolean enabled = false;

        /**
         * HTTPS URL of the git repository containing the config file. SSH is not supported in the initial
         * implementation. Example: {@code https://github.com/myorg/config.git}
         */
        private String url = "";

        /** Branch to check out. Defaults to {@code main}. */
        private String branch = "main";

        /**
         * Path within the repository to the YAML config file. Example: {@code config/git-proxy.yml} or just
         * {@code git-proxy.yml}.
         */
        private String filePath = "git-proxy.yml";

        /**
         * How often (in seconds) to pull and reload from the git repository. Defaults to 300 (5 minutes). Set to 0 to
         * disable periodic refresh (reload only via {@code POST /api/config/reload}).
         */
        private int intervalSeconds = 300;
    }
}
