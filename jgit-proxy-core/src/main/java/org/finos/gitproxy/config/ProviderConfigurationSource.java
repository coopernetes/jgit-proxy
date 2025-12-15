package org.finos.gitproxy.config;

import java.util.List;
import org.finos.gitproxy.provider.GitProxyProvider;

/**
 * Interface for loading provider configuration from various sources (in-memory, configuration files, databases, etc.)
 */
public interface ProviderConfigurationSource {

    /**
     * Get all configured providers
     *
     * @return List of configured providers
     */
    List<GitProxyProvider> getProviders();

    /**
     * Get a specific provider by name
     *
     * @param name Provider name
     * @return The provider or null if not found
     */
    GitProxyProvider getProvider(String name);
}
