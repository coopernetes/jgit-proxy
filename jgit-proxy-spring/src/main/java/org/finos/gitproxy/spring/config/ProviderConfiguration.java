package org.finos.gitproxy.spring.config;

import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.finos.gitproxy.provider.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ProviderConfiguration {

    @Bean
    public ProviderRegistrar providerRegistrar(Environment environment) {
        return new ProviderRegistrar(environment);
    }

    @RequiredArgsConstructor
    public class ProviderRegistrar implements BeanDefinitionRegistryPostProcessor {

        private final Environment environment;

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            BindResult<GitProxyProperties> bindResult =
                    Binder.get(environment).bind("git-proxy", GitProxyProperties.class);
            GitProxyProperties properties = bindResult.get();
            Map<String, GitProxyProvider> providers = properties.getProviders().entrySet().stream()
                    .filter(e -> e.getValue().isEnabled())
                    .map(e -> {
                        var name = e.getKey();
                        var providerCfg = e.getValue();
                        if (!isBuiltin(name)) {
                            return GenericProxyProvider.builder()
                                    .name(name)
                                    .uri(providerCfg.getUri())
                                    .basePath(properties.getBasePath())
                                    .customPath(providerCfg.getServletPath())
                                    .build();
                        } else {
                            return switch (name) {
                                case GitHubProvider.NAME -> new GitHubProvider(properties.getBasePath());
                                case GitLabProvider.NAME -> new GitLabProvider(properties.getBasePath());
                                case BitbucketProvider.NAME -> new BitbucketProvider(properties.getBasePath());
                                default -> throw new IllegalArgumentException("Unknown provider: " + name);
                            };
                        }
                    })
                    .collect(Collectors.toMap(GitProxyProvider::getName, p -> p));
            var definition = new GenericBeanDefinition();
            definition.setBeanClass(ProviderRepository.class);
            definition.setInstanceSupplier(() -> new InMemoryProviderRepository(providers));
            registry.registerBeanDefinition("providerRepository", definition);
        }

        private static boolean isBuiltin(String name) {
            return name.equalsIgnoreCase(GitHubProvider.NAME)
                    || name.equalsIgnoreCase(GitLabProvider.NAME)
                    || name.equalsIgnoreCase(BitbucketProvider.NAME);
        }
    }
}
