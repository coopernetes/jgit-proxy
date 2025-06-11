package org.finos.gitproxy.servlet.filter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.finos.gitproxy.config.GitProxyProperties;
import org.finos.gitproxy.git.HttpOperation;
import org.finos.gitproxy.provider.GitHubProvider;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.provider.ProviderRepository;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
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
    public FilterRegistrationBean<ForceGitClientFilter> forceGitClientFilter(ProviderRepository providerRepository) {
        log.info("Creating ForceGitClientFilter for providers: {}", providerRepository.getProviders());
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
    public FilterRegistrationBean<AuditLogFilter> auditFilter(ProviderRepository providerRepository) {
        log.info("Creating AuditLogFilter for providers: {}", providerRepository.getProviders());
        var urls = providerRepository.getProviders().stream()
                .map(GitProxyProvider::servletMapping)
                .collect(Collectors.toSet());
        var filter = new AuditLogFilter();
        var filterBean = new FilterRegistrationBean<AuditLogFilter>();
        filterBean.setFilter(filter);
        filterBean.setOrder(filter.getOrder());
        filterBean.setUrlPatterns(urls);
        return filterBean;
    }

    @Bean
    public static BeanFactoryPostProcessor providerFilterFactory(
            ApplicationContext applicationContext, Environment environment) {
        BindResult<GitProxyProperties> bindResult = Binder.get(environment).bind("git-proxy", GitProxyProperties.class);
        GitProxyProperties properties = bindResult.get();
        return (beanFactory -> {
            var providerRepo = applicationContext.getBean(ProviderRepository.class);
            log.info(
                    "Creating provider-specific filters for {} providers",
                    providerRepo.getProviders().size());
            for (var provider : providerRepo.getProviders()) {
                var parsePushBean = createParsePushFilter(provider);
                beanFactory.registerSingleton(parsePushBean.getFilter().beanName(), parsePushBean);

                var whitelistFilters = createWhitelistFilters(properties, provider);
                if (!whitelistFilters.isEmpty()) {
                    log.info(
                            "Found {} configured whitelist filters for provider {}",
                            whitelistFilters.size(),
                            provider.getName());

                    int order = whitelistFilters.stream()
                            .mapToInt(WhitelistByUrlFilter::getOrder)
                            .min()
                            .orElse(0);
                    var ops = whitelistFilters.stream()
                            .flatMap(f -> f.applicableOperations.stream())
                            .collect(Collectors.toSet());
                    var whitelistAggregateBean = createWhitelistAggregateBean(provider, order, ops, whitelistFilters);
                    beanFactory.registerSingleton(
                            whitelistAggregateBean.getFilter().beanName(), whitelistAggregateBean);
                }
                // TODO: Generalize this logic
                if (provider instanceof GitHubProvider gitHubProvider
                        && properties.getFilters() != null
                        && properties.getFilters().getGithubUserAuthenticated() != null
                        && properties.getFilters().getGithubUserAuthenticated().isEnabled()
                        && properties
                                .getFilters()
                                .getGithubUserAuthenticated()
                                .getProviders()
                                .contains(gitHubProvider.getName())) {
                    var githubUserAuthBean = createGithubUserAuthBean(
                            gitHubProvider, properties.getFilters().getGithubUserAuthenticated());
                    beanFactory.registerSingleton(githubUserAuthBean.getFilter().beanName(), githubUserAuthBean);
                }
            }
        });
    }

    private static FilterRegistrationBean<ParseRequestFilter> createParsePushFilter(GitProxyProvider provider) {
        var filter = new ParseRequestFilter(provider);
        log.info("Creating {}", filter);
        var filterBean = new FilterRegistrationBean<ParseRequestFilter>();
        filterBean.setName(filter.beanName());
        filterBean.setFilter(filter);
        filterBean.setOrder(filter.getOrder());
        filterBean.addUrlPatterns(provider.servletMapping());
        return filterBean;
    }

    private static FilterRegistrationBean<WhitelistAggregateFilter> createWhitelistAggregateBean(
            GitProxyProvider provider,
            int order,
            Set<HttpOperation> operations,
            List<WhitelistByUrlFilter> whitelistFilters) {
        var filter = new WhitelistAggregateFilter(order, operations, provider, whitelistFilters);
        log.info("Creating {} with {} whitelists", filter, whitelistFilters.size());
        var filterBean = new FilterRegistrationBean<WhitelistAggregateFilter>();
        filterBean.setName(filter.beanName());
        filterBean.setFilter(filter);
        filterBean.setOrder(filter.getOrder());
        filterBean.addUrlPatterns(provider.servletMapping());
        return filterBean;
    }

    private static List<WhitelistByUrlFilter> createWhitelistFilters(
            GitProxyProperties properties, GitProxyProvider provider) {
        return properties.getFilters().getWhitelists().stream()
                .filter(FilterProperties::isEnabled)
                .filter(props -> props.getProviders().contains(provider.getName()) || applyFilterToAll(props))
                .flatMap(props -> {
                    var targets = determineTargets(props);
                    var multipleTargetsInOneConfig = targets.size() > 1;
                    if (multipleTargetsInOneConfig) {
                        log.warn(
                                "Multiple targets has been configured for this a single whitelist filter ({}), order will be adjusted for specificity",
                                props);
                    }
                    return targets.stream()
                            .map(target -> {
                                int order = props.getOrder();
                                // The general logic here is that for a single configuration of a WhitelistFilters,
                                // the order is modified for them to apply from most specific (slugs) to
                                // least specific (repo names). This runs the risk of being too implicit and
                                // confusing if other filter orders are not set correctly or if the order modified
                                // begins colliding with other filters in the overall chain. The best thing to do is to
                                // set a unique order for each filter and chose one target type.
                                if (multipleTargetsInOneConfig && target == AuthorizedByUrlFilter.Target.NAME) {
                                    order += 2;
                                }
                                if (multipleTargetsInOneConfig && target == AuthorizedByUrlFilter.Target.OWNER) {
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
                .collect(Collectors.toList());
    }

    private static FilterRegistrationBean<GitHubUserAuthenticatedFilter> createGithubUserAuthBean(
            GitHubProvider gitHubProvider,
            GitProxyProperties.GitHubUserAuthenticatedFilterProperties filterProperties) {
        GitHubUserAuthenticatedFilter filter;
        if (filterProperties.getOperations() != null
                && !filterProperties.getOperations().isEmpty()) {
            filter = new GitHubUserAuthenticatedFilter(
                    filterProperties.getOrder(),
                    filterProperties.getOperations(),
                    gitHubProvider,
                    filterProperties.getRequiredAuthSchemes());
        } else {
            filter = new GitHubUserAuthenticatedFilter(
                    filterProperties.getOrder(), gitHubProvider, filterProperties.getRequiredAuthSchemes());
        }
        log.info("Creating {}", filter);
        var filterBean = new FilterRegistrationBean<GitHubUserAuthenticatedFilter>();
        filterBean.setFilter(filter);
        filterBean.setOrder(filterProperties.getOrder());
        filterBean.addUrlPatterns(gitHubProvider.servletMapping());
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

    private static boolean applyFilterToAll(FilterProperties filterProperties) {
        return filterProperties.getProviders().size() == 1
                && filterProperties.getProviders().contains("*");
    }
}
