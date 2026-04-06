package org.finos.gitproxy.dashboard;

import java.util.Collection;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.userdetails.LdapUserDetailsMapper;

/**
 * Extends the default LDAP user-details mapper to capture the {@code mail} attribute from the directory entry. When
 * present, wraps the result in {@link LdapUserDetailsWithEmail} so that {@link IdpLoginListener} can lock that email
 * address to the user's profile on login.
 *
 * <p>Requires the {@link org.springframework.security.ldap.authentication.BindAuthenticator} to be configured with
 * {@code setUserAttributesToReturn(new String[]{"mail"})} so the attribute is fetched during bind.
 */
class LdapEmailContextMapper extends LdapUserDetailsMapper {

    @Override
    public UserDetails mapUserFromContext(
            DirContextOperations ctx, String username, Collection<? extends GrantedAuthority> authorities) {
        UserDetails base = super.mapUserFromContext(ctx, username, authorities);
        String mail = ctx.getStringAttribute("mail");
        if (mail == null || mail.isBlank()) {
            return base;
        }
        return new LdapUserDetailsWithEmail(base, mail);
    }
}
