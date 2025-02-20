package com.rbc.jgitproxy.servlet.filter;

import com.rbc.jgitproxy.config.GitProxyProperties;
import com.rbc.jgitproxy.provider.GitHubProvider;
import com.rbc.jgitproxy.provider.GitProxyProvider;
import com.rbc.jgitproxy.provider.ProviderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@Slf4j
public class FilterConfiguration {

    @Bean
    public FilterRegistrationBean<ForceGitClientFilter> forceGitClientFilter(ProviderRepository providerRepository) {
        var urls = providerRepository.getProviders().stream()
                .map(GitProxyProvider::servletMapping)
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
            var providerRepo = applicationContext.getBean(ProviderRepository.class);
            for (var provider : providerRepo.getProviders()) {
                var whitelistFilters = createWhitelistFilters(properties, provider);
                if (!whitelistFilters.isEmpty()) {
                    log.debug(
                            "Creating {} whitelist filters for provider: {}",
                            whitelistFilters.size(),
                            provider.getName());
                    whitelistFilters.forEach(
                            reg -> beanFactory.registerSingleton(reg.getFilter().beanName(), reg));
                }
                // TODO: Generalize this logic
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
                    var registrationBean =
                            create(gitHubProvider, properties.getFilters().getGithubRequiredAuthentication());
                    beanFactory.registerSingleton(registrationBean.getFilter().beanName(), registrationBean);
                }
            }
        });
    }

    private static List<FilterRegistrationBean<WhitelistByUrlFilter>> createWhitelistFilters(
            GitProxyProperties properties, GitProxyProvider provider) {
        return properties.getFilters().getWhitelists().stream()
                .filter(FilterProperties::isEnabled)
                .filter(props -> props.getProviders().contains(provider.getName()))
                .flatMap(props -> {
                    var targets = determineTargets(props);
                    return targets.stream()
                            .map(target -> {
                                int order = props.getOrder();
                                // TODO: Provide a less implicit way to customize or define this behaviour.
                                // The general logic here is that for a single configuration of a group of
                                // WhitelistFilters, the order modified for them to apply from most specific (slugs) to
                                // least specific (repo names). This runs the risk of being too implicit and
                                // confusing if other filter orders are not set correctly or if the order modified
                                // begins colliding with other filters in the overall chain.
                                if (target == AuthorizedByUrlFilter.Target.NAME) {
                                    order += 2;
                                }
                                if (target == AuthorizedByUrlFilter.Target.OWNER) {
                                    order += 1;
                                }
                                if (props.getOperations() != null) {
                                    return new WhitelistByUrlFilter(
                                            order,
                                            props.getOperations(),
                                            provider,
                                            props.getWhitelistForTarget(target),
                                            target);
                                }
                                return new WhitelistByUrlFilter(
                                        order, provider, props.getWhitelistForTarget(target), target);
                            })
                            .toList()
                            .stream();
                })
                .map(filter -> create(provider, filter, filter.getOrder()))
                .collect(Collectors.toList());
    }

    private static FilterRegistrationBean<WhitelistByUrlFilter> create(
            GitProxyProvider provider, WhitelistByUrlFilter filter, int order) {
        log.debug("Creating {} for provider {}", filter, provider);
        var filterBean = new FilterRegistrationBean<WhitelistByUrlFilter>();
        filterBean.setName(filter.beanName());
        filterBean.setFilter(filter);
        filterBean.setOrder(order);
        filterBean.addUrlPatterns(provider.servletPath());
        return filterBean;
    }

    private static FilterRegistrationBean<GitHubRequiredAuthenticationFilter> create(
            GitHubProvider gitHubProvider,
            GitProxyProperties.GitHubRequiredAuthenticationFilterProperties filterProperties) {
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

    private static Set<AuthorizedByUrlFilter.Target> determineTargets(
            GitProxyProperties.WhitelistFilterProperties filterProperties) {
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
