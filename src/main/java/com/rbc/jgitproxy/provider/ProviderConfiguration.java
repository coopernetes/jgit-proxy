package com.rbc.jgitproxy.provider;

import com.rbc.jgitproxy.config.GitProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@Slf4j
@EnableConfigurationProperties(GitProxyProperties.class)
public class ProviderConfiguration {

    @Bean
    public static BeanFactoryPostProcessor registerProviderRepository() {
        return (beanFactory -> {
            var providerBeans = beanFactory.getBeansOfType(GitProxyProvider.class);
            beanFactory.registerSingleton(
                    "providerRepository",
                    new InMemoryProviderRepository(providerBeans.values().stream()
                            .collect(Collectors.toMap(GitProxyProvider::getName, p -> p))));
            ;
        });
    }

    @Bean
    public static BeanFactoryPostProcessor customProviderConfiguration(Environment environment) {
        return (beanFactory -> {
            BindResult<GitProxyProperties> bindResult =
                    Binder.get(environment).bind("git-proxy", GitProxyProperties.class);
            GitProxyProperties properties = bindResult.get();
            properties.getProviders().entrySet().stream()
                    .filter(e -> e.getValue().isEnabled())
                    .filter(e -> !isBuiltin(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                    .forEach((name, providerCfg) -> {
                        Assert.notNull(providerCfg.getUri(), "URI is required for custom provider");
                        var customProvider = GenericProxyProvider.builder()
                                .name(name)
                                .uri(providerCfg.getUri())
                                .basePath(properties.getBasePath());
                        if (providerCfg.getServletPath() != null) {
                            customProvider.customPath(providerCfg.getServletPath());
                        }
                        beanFactory.registerSingleton(name, customProvider.build());
                    });
        });
    }

    @Bean
    @ConditionalOnProperty(prefix = "git-proxy.providers.github", name = "enabled", havingValue = "true")
    public GitHubProvider githubProvider(GitProxyProperties properties) {
        return new GitHubProvider(properties.getBasePath());
    }

    @Bean
    @ConditionalOnProperty(prefix = "git-proxy.providers.gitlab", name = "enabled", havingValue = "true")
    public GitLabProvider gitlabProvider(GitProxyProperties properties) {
        return new GitLabProvider(properties.getBasePath());
    }

    @Bean
    @ConditionalOnProperty(prefix = "git-proxy.providers.bitbucket", name = "enabled", havingValue = "true")
    public BitbucketProvider bitbucketProvider(GitProxyProperties properties) {
        return new BitbucketProvider(properties.getBasePath());
    }

    private static boolean isBuiltin(String name) {
        return name.equalsIgnoreCase(GitHubProvider.NAME)
                || name.equalsIgnoreCase(GitLabProvider.NAME)
                || name.equalsIgnoreCase(BitbucketProvider.NAME);
    }
}
