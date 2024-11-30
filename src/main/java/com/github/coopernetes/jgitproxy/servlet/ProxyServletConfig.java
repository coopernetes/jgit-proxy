package com.github.coopernetes.jgitproxy.servlet;

import com.github.coopernetes.jgitproxy.github.GithubAuthenticationFilter;
import com.github.coopernetes.jgitproxy.github.GithubFetchAuthorizedOwnerFilter;
import com.github.coopernetes.jgitproxy.github.GithubProperties;
import com.github.coopernetes.jgitproxy.github.rest.GithubClient;
import org.mitre.dsmiley.httpproxy.ProxyServlet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class ProxyServletConfig {

    @Bean
    public ServletRegistrationBean<RewritingProxyServlet> githubServlet() {
        return createProxyServlet(GitHttpProviders.GITHUB);
    }

    @Bean
    public ServletRegistrationBean<RewritingProxyServlet> gitlabServlet() {
        return createProxyServlet(GitHttpProviders.GITLAB);
    }

    @Bean
    public ServletRegistrationBean<RewritingProxyServlet> bitbucketServlet() {
        return createProxyServlet(GitHttpProviders.BITBUCKET);
    }

    @Bean
    @Order(2)
    public FilterRegistrationBean<GithubAuthenticationFilter> githubAuthenticationFilter(GithubClient githubClient) {
        var filterBean = new FilterRegistrationBean<GithubAuthenticationFilter>();
        filterBean.setFilter(new GithubAuthenticationFilter(githubClient));
        filterBean.addUrlPatterns("/github.com/*");
        return filterBean;
    }

    @Bean
    @Order(1)
    public FilterRegistrationBean<GithubFetchAuthorizedOwnerFilter> githubFetchAuthorizedOrgFilter(
            GithubProperties properties) {
        var filterBean = new FilterRegistrationBean<GithubFetchAuthorizedOwnerFilter>();
        filterBean.setFilter(
                new GithubFetchAuthorizedOwnerFilter(properties.getFetch().getAuthorisedOwners()));
        filterBean.addUrlPatterns("/github.com/*");
        return filterBean;
    }

    /**
     * Creates a RewritingProxyServlet for a given git web provider. These servlets
     * assume that GitProxy is proxying an upstream HTTPS server directly with a
     * matching (corresponding) URL path. It does not do any other sort of
     * manipulation. To manipulate the handling of a given git HTTPS request, you
     * must implement that through Filters.
     *
     * @param provider
     *            the given provider enum
     * @return the servlet registration bean for the provider
     */
    private ServletRegistrationBean<RewritingProxyServlet> createProxyServlet(GitHttpProviders provider) {
        return createRegistration(
                provider.name().toLowerCase(),
                String.format("/%s/*", provider.getHostname()),
                String.format("https://%s", provider.getHostname()));
    }

    private ServletRegistrationBean<RewritingProxyServlet> createRegistration(
            String name, String urlPath, String targetUri) {
        var proxyServlet = new RewritingProxyServlet();
        var registration = new ServletRegistrationBean<>(proxyServlet, urlPath);
        registration.setName(name);
        registration.addInitParameter(ProxyServlet.P_TARGET_URI, targetUri);
        registration.addInitParameter(ProxyServlet.P_LOG, "true");
        return registration;
    }
}
