package org.finos.gitproxy.servlet.filter;

import org.eclipse.jgit.lib.Constants;
import org.springframework.util.Assert;

public interface RepositoryUrlFilter {

    enum Target {
        OWNER,
        NAME,
        SLUG;
    }

    default String getOwner(String pathInfo) {
        var parts = splitPathInfo(pathInfo);
        return parts[1];
    }

    default String getName(String pathInfo) {
        var parts = splitPathInfo(pathInfo);
        return parts[2].replace(Constants.DOT_GIT_EXT, "");
    }

    default String getSlug(String pathInfo) {
        return String.join("/", getOwner(pathInfo), getName(pathInfo));
    }

    private String[] splitPathInfo(String pathInfo) {
        Assert.notNull(pathInfo, "URI must not be null");
        var parts = pathInfo.split("/");
        Assert.isTrue(
                parts.length >= 2,
                "URI must have at least 3 parts to be used by this filter ("
                        + this.getClass().getSimpleName() + ")");
        return parts;
    }
}
