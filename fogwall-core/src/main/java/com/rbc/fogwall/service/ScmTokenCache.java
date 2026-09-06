package com.rbc.fogwall.service;

import java.util.Optional;

public interface ScmTokenCache {

    Optional<CachedScmIdentity> lookup(String provider, String tokenHash);

    void store(String provider, String tokenHash, CachedScmIdentity identity);

    void evictByUsername(String provider, String proxyUsername);
}
