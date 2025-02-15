package com.github.coopernetes.jgitproxy.provider;

import com.github.coopernetes.jgitproxy.config.GitProxyProperties;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

@Configuration
@Slf4j
public class ProviderConfiguration {

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
                    .forEach((name, customProvider) -> {
                        Assert.notNull(customProvider.getUri(), "URI is required for custom provider");
                        if (customProvider.getServletPath() != null) {
                            beanFactory.registerSingleton(
                                    name,
                                    new GenericProxyProvider(
                                            name, customProvider.getUri(), customProvider.getServletPath()));
                        } else {
                            beanFactory.registerSingleton(
                                    name, new GenericProxyProvider(name, customProvider.getUri()));
                        }
                    });
        });
    }

    @Bean
    @ConditionalOnProperty(prefix = "git-proxy.providers.github", name = "enabled", havingValue = "true")
    public GitHubProvider githubProvider() {
        return new GitHubProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "git-proxy.providers.gitlab", name = "enabled", havingValue = "true")
    public GitLabProvider gitlabProvider() {
        return new GitLabProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "git-proxy.providers.bitbucket", name = "enabled", havingValue = "true")
    public BitbucketProvider bitbucketProvider() {
        return new BitbucketProvider();
    }

    private static boolean isBuiltin(String name) {
        return name.equalsIgnoreCase(GitHubProvider.NAME)
                || name.equalsIgnoreCase(GitLabProvider.NAME)
                || name.equalsIgnoreCase(BitbucketProvider.NAME);
    }
}
