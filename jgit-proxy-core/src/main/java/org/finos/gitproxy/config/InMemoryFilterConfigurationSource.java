package org.finos.gitproxy.config;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.finos.gitproxy.servlet.filter.GitProxyFilter;

/** Simple in-memory implementation of FilterConfigurationSource */
@RequiredArgsConstructor
public class InMemoryFilterConfigurationSource implements FilterConfigurationSource {

    private final Map<String, List<GitProxyFilter>> providerFilters;
    private final List<GitProxyFilter> globalFilters;

    public InMemoryFilterConfigurationSource() {
        this.providerFilters = new HashMap<>();
        this.globalFilters = new ArrayList<>();
    }

    @Override
    public List<GitProxyFilter> getFiltersForProvider(String providerName) {
        List<GitProxyFilter> filters = new ArrayList<>(globalFilters);
        if (providerFilters.containsKey(providerName)) {
            filters.addAll(providerFilters.get(providerName));
        }
        return filters;
    }

    @Override
    public List<GitProxyFilter> getAllFilters() {
        List<GitProxyFilter> allFilters = new ArrayList<>(globalFilters);
        providerFilters.values().forEach(allFilters::addAll);
        return allFilters;
    }
}
