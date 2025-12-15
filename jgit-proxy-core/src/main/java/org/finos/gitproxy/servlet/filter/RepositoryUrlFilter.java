package org.finos.gitproxy.servlet.filter;

import org.eclipse.jgit.lib.Constants;

/**
 * A filter that extracts repository information from the request path. This interface provides methods to retrieve the
 * owner, name, and slug of a repository based on the path information provided in the request.
 *
 * <p>Not every provider will have all of these parts, so implementations should handle cases where the owner or name
 * may not be present.
 */
public interface RepositoryUrlFilter {

    enum Target {
        OWNER,
        NAME,
        SLUG;
    }

    /**
     * Extracts the owner from the path information. This method assumes that the owner name is the second part of the
     * path, which is common in many Git hosting services.
     *
     * @param pathInfo {@link jakarta.servlet.http.HttpServletRequest#getPathInfo()} from the incoming request
     * @return the owner part of the path, or the whole path if the owner is not present
     */
    default String getOwner(String pathInfo) {
        var parts = pathInfo.split("/");
        if (parts.length < 3) {
            return pathInfo;
        }
        return parts[1];
    }

    /**
     * Extracts the name of the repository from the path information. This method assumes that the repository name is
     * the third part of the path, which is common in many Git hosting services.
     *
     * @param pathInfo {@link jakarta.servlet.http.HttpServletRequest#getPathInfo()} from the incoming request
     * @return the name of the repository, or the whole path if the name is not present
     */
    default String getName(String pathInfo) {
        var parts = pathInfo.split("/");
        if (parts.length < 3) {
            return pathInfo; // If there is no owner, return the whole pathInfo
        }
        return parts[2].replace(Constants.DOT_GIT_EXT, "");
    }

    /**
     * Extracts the slug from the path information. This method assumes that the slug is a combination of the owner and
     * repository name, which is common in many Git hosting services. If the path does not contain enough parts or is
     * not structured as expected, it returns the whole pathInfo.
     *
     * @param pathInfo {@link jakarta.servlet.http.HttpServletRequest#getPathInfo()} from the incoming request
     * @return the slug, which is a combination of the owner and repository name, or the whole path
     */
    default String getSlug(String pathInfo) {
        var parts = pathInfo.split("/");
        if (parts.length < 3) {
            return pathInfo;
        }
        return String.join("/", parts[1], parts[2]).replace(Constants.DOT_GIT_EXT, "");
    }
}
