package com.rbc.fogwall.build;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version and commit this process was built from, read once from a classpath resource Gradle expands at build time.
 *
 * <p>Lives in {@code fogwall-core} so both applications resolve the same two values from the same file. The dashboard's
 * own {@code version.properties} could not serve the standalone server: it is not on that distribution's classpath, and
 * a second resource of the same name would shadow the dashboard's rather than supplement it.
 *
 * <p>Either value can be {@value #UNKNOWN}, and a process that cannot name its build still runs. The container build
 * has no {@code git} binary, so the commit arrives as a Gradle property the image build passes in — a build run without
 * it produces a working artifact that simply cannot identify itself, which is preferable to failing the build.
 */
public final class BuildInfo {

    /** Stand-in for a value the build did not establish. */
    public static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "fogwall-build.properties";
    private static final int SHORT_COMMIT_LENGTH = 7;

    private static final BuildInfo INSTANCE = load();

    private final String version;
    private final String commit;

    private BuildInfo(String version, String commit) {
        this.version = version;
        this.commit = commit;
    }

    /** The values for this process. */
    public static BuildInfo get() {
        return INSTANCE;
    }

    /** The released version, e.g. {@code 1.3.2}, or {@value #UNKNOWN}. */
    public String version() {
        return version;
    }

    /** The full commit SHA, or {@value #UNKNOWN}. */
    public String commit() {
        return commit;
    }

    /** The commit abbreviated for display, or {@value #UNKNOWN} when there is none. */
    public String shortCommit() {
        return UNKNOWN.equals(commit) ? UNKNOWN : commit.substring(0, Math.min(SHORT_COMMIT_LENGTH, commit.length()));
    }

    /** {@code <version> (<short commit>)} — for a startup line or a diagnostic banner. */
    public String display() {
        return version + " (" + shortCommit() + ")";
    }

    private static BuildInfo load() {
        var props = new Properties();
        try (InputStream in = BuildInfo.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Falls through to UNKNOWN for both values.
        }
        return from(props);
    }

    /** Builds from already-loaded properties, so the parsing rules are reachable without a classpath resource. */
    static BuildInfo from(Properties props) {
        return new BuildInfo(clean(props.getProperty("version")), clean(props.getProperty("commit")));
    }

    /**
     * Normalises a raw property value. An unexpanded {@code ${...}} placeholder counts as unset — that is what a
     * classpath assembled without running {@code processResources} yields, and reporting the literal placeholder as a
     * version would be worse than admitting the build is unknown.
     */
    private static String clean(String value) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            return UNKNOWN;
        }
        return value.trim();
    }
}
