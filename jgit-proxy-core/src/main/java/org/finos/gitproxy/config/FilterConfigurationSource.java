package org.finos.gitproxy.config;

import java.util.List;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;

/** Interface for loading filter configuration from various sources (in-memory, configuration files, databases, etc.) */
public interface FilterConfigurationSource {

    /**
     * Get all configured filters for a specific provider
     *
     * @param providerName Name of the provider
     * @return List of filters to apply for this provider
     */
    List<GitProxyFilter> getFiltersForProvider(String providerName);

    /**
     * Get all configured filters
     *
     * @return List of all filters
     */
    List<GitProxyFilter> getAllFilters();
}
