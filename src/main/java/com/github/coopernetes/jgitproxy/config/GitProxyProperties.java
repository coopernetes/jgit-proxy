package com.github.coopernetes.jgitproxy.config;

import com.github.coopernetes.jgitproxy.servlet.filter.GitHubRequiredAuthenticationFilterProperties;
import com.github.coopernetes.jgitproxy.servlet.filter.WhitelistFilterProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(value = "git-proxy", ignoreUnknownFields = false)
@Getter
@Setter
@ToString
public class GitProxyProperties {

    private Map<String, Provider> providers = new HashMap<>();
    private Filters filters;

    /**
     * Whether to proxy requests that are not Git requests. If this is set to false, then only Git requests will be
     * proxied. If this is set to true, then all requests will be proxied to the target Git server including web browser
     * requests.
     */
    private boolean allowNonGitRequests = false;

    /**
     * Configuration properties for the GitProxy providers. Each provider option is designed to customize the actual
     * underlying proxy servlet that will be created for each provider. Most of the options exposed here match the list
     * of supported init parameters in {@link org.mitre.dsmiley.httpproxy.ProxyServlet}.
     */
    @Getter
    @Setter
    @ToString
    public static class Provider {
        private boolean enabled = false;
        private URI uri;
        private String servletPath = "";
        private boolean logProxy = true;
        private int connectTimeout = -1;
        private int readTimeout = -1;
    }

    /**
     * Properties for configurable filters that can be applied to the proxy servlets. Each filter has a set of common
     * properties that can be used to configure the behavior of the filter as well as specific properties that are
     * unique to the filter. Each filter is expected to be configured against a list of providers that it should be
     * applied to.
     */
    @Getter
    @Setter
    @ToString
    public static class Filters {
        private List<WhitelistFilterProperties> whitelists = new ArrayList<>();
        private GitHubRequiredAuthenticationFilterProperties githubRequiredAuthentication;
    }
}
