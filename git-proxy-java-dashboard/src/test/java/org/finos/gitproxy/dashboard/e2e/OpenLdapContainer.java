package org.finos.gitproxy.dashboard.e2e;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Testcontainers wrapper for <a href="https://github.com/osixia/container-openldap">osixia/openldap</a>.
 *
 * <p>Starts with a single test user ({@value #TEST_USER} / {@value #TEST_PASSWORD}) added via {@code ldapadd} after
 * container startup. The user is created at {@code cn=testuser,ou=users,dc=example,dc=com} and is a member of the
 * {@value #ADMIN_GROUP} group at {@code cn=admins,ou=groups,dc=example,dc=com}.
 *
 * <p>TLS is disabled ({@code LDAP_TLS=false}) so the test JVM can connect over plain {@code ldap://} without needing to
 * trust a self-signed certificate. This is intentional for test containers only.
 *
 * <p>Corresponding Spring Security LDAP config:
 *
 * <pre>
 * url: ldap://localhost:{mappedPort}/dc=example,dc=com
 * userDnPatterns: cn={0},ou=users
 * </pre>
 */
@SuppressWarnings("resource")
class OpenLdapContainer extends GenericContainer<OpenLdapContainer> {

    static final String TEST_USER = "testuser";
    static final String TEST_PASSWORD = "testpass123";

    static final String BASE_DN = "dc=example,dc=com";
    static final String USER_DN_PATTERN = "cn={0},ou=users";

    /**
     * LDAP search filter for the search-first authentication path (analogous to {@code (sAMAccountName={0})} on Active
     * Directory). {@code {0}} is substituted with the login username by Spring Security.
     */
    static final String USER_SEARCH_FILTER = "(cn={0})";

    /** Base DN (relative to {@link #BASE_DN}) to scope user searches. */
    static final String USER_SEARCH_BASE = "ou=users";

    /** Base DN for group search (relative to {@link #BASE_DN}). */
    static final String GROUP_SEARCH_BASE = "ou=groups";

    /** CN of the test group that {@link #TEST_USER} belongs to. */
    static final String ADMIN_GROUP = "admins";

    /** Manager DN for authenticated group searches. */
    static final String MANAGER_DN = "cn=admin," + BASE_DN;

    static final String ADMIN_PASSWORD = "adminpassword";

    private static final int LDAP_PORT = 389;

    private static final String BOOTSTRAP_LDIF = "dn: ou=users," + BASE_DN + "\n"
            + "objectClass: organizationalUnit\n"
            + "ou: users\n"
            + "\n"
            + "dn: cn=" + TEST_USER + ",ou=users," + BASE_DN + "\n"
            + "objectClass: inetOrgPerson\n"
            + "cn: " + TEST_USER + "\n"
            + "sn: " + TEST_USER + "\n"
            + "userPassword: " + TEST_PASSWORD + "\n"
            + "\n"
            + "dn: ou=groups," + BASE_DN + "\n"
            + "objectClass: organizationalUnit\n"
            + "ou: groups\n"
            + "\n"
            + "dn: cn=" + ADMIN_GROUP + ",ou=groups," + BASE_DN + "\n"
            + "objectClass: groupOfNames\n"
            + "cn: " + ADMIN_GROUP + "\n"
            + "member: cn=" + TEST_USER + ",ou=users," + BASE_DN + "\n";

    OpenLdapContainer() {
        super("docker.io/osixia/openldap:1.5.0");
        withEnv("LDAP_DOMAIN", "example.com");
        withEnv("LDAP_ADMIN_PASSWORD", ADMIN_PASSWORD);
        withEnv("LDAP_TLS", "false");
        withExposedPorts(LDAP_PORT);
        waitingFor(Wait.forListeningPort());
    }

    @Override
    public void start() {
        super.start();
        seedTestUsers();
    }

    private void seedTestUsers() {
        try {
            copyFileToContainer(Transferable.of(BOOTSTRAP_LDIF), "/tmp/users.ldif");
            var result = execInContainer(
                    "ldapadd",
                    "-x",
                    "-H",
                    "ldap://localhost",
                    "-D",
                    "cn=admin," + BASE_DN,
                    "-w",
                    ADMIN_PASSWORD,
                    "-f",
                    "/tmp/users.ldif");
            if (result.getExitCode() != 0) {
                throw new RuntimeException("ldapadd failed (exit " + result.getExitCode() + "): " + result.getStderr());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to seed LDAP test users", e);
        }
    }

    /**
     * LDAP URL (with base DN) for use in Spring Security config, e.g. {@code ldap://localhost:12345/dc=example,dc=com}.
     */
    String getLdapUrl() {
        return "ldap://" + getHost() + ":" + getMappedPort(LDAP_PORT) + "/" + BASE_DN;
    }
}
