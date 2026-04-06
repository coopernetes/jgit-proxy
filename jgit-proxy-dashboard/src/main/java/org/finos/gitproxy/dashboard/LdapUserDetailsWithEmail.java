package org.finos.gitproxy.dashboard;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Thin {@link UserDetails} wrapper that carries the LDAP {@code mail} attribute alongside the standard Spring Security
 * fields. Produced by {@link LdapEmailContextMapper} and consumed by {@link IdpLoginListener} to discover the email to
 * lock against the user record.
 */
class LdapUserDetailsWithEmail extends User {

    private final String email;

    LdapUserDetailsWithEmail(UserDetails delegate, String email) {
        super(
                delegate.getUsername(),
                delegate.getPassword() != null ? delegate.getPassword() : "",
                delegate.getAuthorities());
        this.email = email;
    }

    String getEmail() {
        return email;
    }
}
