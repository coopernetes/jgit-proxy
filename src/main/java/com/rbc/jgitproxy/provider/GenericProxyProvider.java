package com.rbc.jgitproxy.provider;

import java.net.URI;

public class GenericProxyProvider extends AbstractGitProxyProvider {

    GenericProxyProvider(String name, URI uri, String basePath, String customPath) {
        super(name, uri, basePath, customPath);
    }

    public static GenericProxyProvider.Builder builder() {
        return new GenericProxyProvider.Builder();
    }

    public static class Builder {
        private String name;
        private URI uri;
        private String basePath;
        private String customPath;

        public GenericProxyProvider.Builder name(String name) {
            this.name = name;
            return this;
        }

        public GenericProxyProvider.Builder uri(URI uri) {
            this.uri = uri;
            return this;
        }

        public GenericProxyProvider.Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public GenericProxyProvider.Builder customPath(String customPath) {
            this.customPath = customPath;
            return this;
        }

        public GenericProxyProvider build() {
            return new GenericProxyProvider(this.name, this.uri, this.basePath, this.customPath);
        }
    }
}
