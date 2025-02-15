package com.github.coopernetes.jgitproxy.servlet.filter;

import com.github.coopernetes.jgitproxy.config.GitProxyProperties;
import com.github.coopernetes.jgitproxy.provider.GitHubProvider;
import com.github.coopernetes.jgitproxy.provider.GitProxyProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@Slf4j
public class FilterConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "git-proxy",
            name = "allow-non-git-requests",
            havingValue = "false",
            matchIfMissing = true)
    public FilterRegistrationBean<ForceGitClientFilter> forceGitClientFilter(ApplicationContext applicationContext) {
        var urls = applicationContext.getBeansOfType(GitProxyProvider.class).values().stream()
                .map(GitProxyProvider::servletPath)
                .collect(Collectors.toSet());
        var filter = new ForceGitClientFilter();
        var filterBean = new FilterRegistrationBean<ForceGitClientFilter>();
        filterBean.setFilter(filter);
        filterBean.setOrder(filter.getOrder());
        filterBean.setUrlPatterns(urls);
        return filterBean;
    }

    @Bean
    public static BeanFactoryPostProcessor configurationBasedFilterFactory(
            ApplicationContext applicationContext, Environment environment) {
        BindResult<GitProxyProperties> bindResult = Binder.get(environment).bind("git-proxy", GitProxyProperties.class);
        GitProxyProperties properties = bindResult.get();
        return (beanFactory -> {
            var providerBeanMap = applicationContext.getBeansOfType(GitProxyProvider.class);
            for (var provider : providerBeanMap.values()) {
                var whitelistFilters = createWhitelistFilters(properties, provider);
                if (!whitelistFilters.isEmpty()) {
                    log.debug(
                            "Creating {} whitelist filters for provider: {}",
                            whitelistFilters.size(),
                            provider.getName());
                    whitelistFilters.forEach(
                            reg -> beanFactory.registerSingleton(reg.getFilter().beanName(), reg));
                }
                if (provider instanceof GitHubProvider gitHubProvider
                        && properties.getFilters() != null
                        && properties.getFilters().getGithubRequiredAuthentication() != null
                        && properties
                                .getFilters()
                                .getGithubRequiredAuthentication()
                                .isEnabled()
                        && properties
                                .getFilters()
                                .getGithubRequiredAuthentication()
                                .getProviders()
                                .contains(gitHubProvider.getName())) {
                    var githubRequiredAuthenticationFilter =
                            create(gitHubProvider, properties.getFilters().getGithubRequiredAuthentication());
                    beanFactory.registerSingleton(
                            githubRequiredAuthenticationFilter.getFilter().beanName(),
                            githubRequiredAuthenticationFilter);
                }
            }
        });
    }

    private static List<FilterRegistrationBean<WhitelistFilter>> createWhitelistFilters(
            GitProxyProperties properties, GitProxyProvider provider) {
        return properties.getFilters().getWhitelists().stream()
                .filter(props -> props.getProviders().contains(provider.getName()))
                .flatMap(props -> {
                    var targets = determineTargets(props);
                    return targets.stream()
                            .map(target -> {
                                if (props.getOperations() != null) {
                                    return new WhitelistFilter(
                                            props.getOrder(),
                                            props.getOperations(),
                                            provider,
                                            props.getWhitelistForTarget(target),
                                            target);
                                }
                                return new WhitelistFilter(
                                        props.getOrder(), provider, props.getWhitelistForTarget(target), target);
                            })
                            .toList()
                            .stream();
                })
                .map(filter -> create(provider, filter, filter.getOrder()))
                .collect(Collectors.toList());
    }

    private static FilterRegistrationBean<WhitelistFilter> create(
            GitProxyProvider provider, WhitelistFilter filter, int order) {
        log.debug("Creating {} for provider {}", filter, provider);
        var filterBean = new FilterRegistrationBean<WhitelistFilter>();
        filterBean.setName(filter.beanName());
        filterBean.setFilter(filter);
        filterBean.setOrder(order);
        filterBean.addUrlPatterns(provider.servletPath());
        return filterBean;
    }

    private static FilterRegistrationBean<GitHubRequiredAuthenticationFilter> create(
            GitHubProvider gitHubProvider, GitHubRequiredAuthenticationFilterProperties filterProperties) {
        GitHubRequiredAuthenticationFilter filter;
        if (filterProperties.getOperations() != null
                && !filterProperties.getOperations().isEmpty()) {
            filter = new GitHubRequiredAuthenticationFilter(
                    filterProperties.getOrder(), filterProperties.getOperations(), gitHubProvider);
        } else {
            filter = new GitHubRequiredAuthenticationFilter(filterProperties.getOrder(), gitHubProvider);
        }

        var filterBean = new FilterRegistrationBean<GitHubRequiredAuthenticationFilter>();
        filterBean.setFilter(filter);
        filterBean.setOrder(filterProperties.getOrder());
        filterBean.addUrlPatterns(gitHubProvider.servletPath());
        return filterBean;
    }

    private static Set<AuthorizedByUrlFilter.Target> determineTargets(WhitelistFilterProperties filterProperties) {
        var targets = new HashSet<AuthorizedByUrlFilter.Target>();
        if (!filterProperties.getNames().isEmpty()) {
            targets.add(AuthorizedByUrlFilter.Target.NAME);
        }
        if (!filterProperties.getOwners().isEmpty()) {
            targets.add(AuthorizedByUrlFilter.Target.OWNER);
        }
        if (!filterProperties.getSlugs().isEmpty()) {
            targets.add(AuthorizedByUrlFilter.Target.SLUG);
        }
        return targets;
    }
}
