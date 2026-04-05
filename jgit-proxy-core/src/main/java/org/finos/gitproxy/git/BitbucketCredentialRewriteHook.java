package org.finos.gitproxy.git;

import java.util.Collection;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.provider.BitbucketProvider;
import org.finos.gitproxy.provider.client.ScmUserInfo;

/**
 * Pre-receive hook that resolves the Bitbucket push user's upstream username and stores it in the repository config
 * ({@code gitproxy.upstreamUser}) for use by {@link ForwardingPostReceiveHook}.
 *
 * <p>Bitbucket does not validate the HTTP Basic-auth username on git pushes — only the token is checked. The proxy
 * convention is that the username field carries the user's Bitbucket account email. This hook calls {@code GET
 * /2.0/user} using that email and the push token to retrieve the auto-generated {@code username} field that Bitbucket
 * recognises for git authentication.
 *
 * <p>{@link ForwardingPostReceiveHook} reads {@code gitproxy.upstreamUser} and rewrites the outbound credentials from
 * {@code email:token} to {@code username:token} before pushing to Bitbucket upstream.
 *
 * <p>Runs at order 148, before {@link CheckUserPushPermissionHook} (150).
 */
@Slf4j
@RequiredArgsConstructor
public class BitbucketCredentialRewriteHook implements GitProxyHook {

    private static final int ORDER = 148;

    private final BitbucketProvider provider;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return "bitbucketCredentialRewrite";
    }

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        var repo = rp.getRepository();
        String pushEmail = repo.getConfig().getString("gitproxy", null, "pushUser");
        String pushToken = repo.getConfig().getString("gitproxy", null, "pushToken");

        if (pushEmail == null || pushToken == null) {
            log.debug("No push credentials in repo config — skipping Bitbucket upstream username resolution");
            return;
        }

        Optional<ScmUserInfo> identity = provider.fetchScmIdentity(pushEmail, pushToken);
        if (identity.isEmpty()) {
            log.debug(
                    "Could not resolve Bitbucket upstream username for '{}' — credentials will be forwarded as-is",
                    pushEmail);
            return;
        }

        String upstreamUser = identity.get().login();
        repo.getConfig().setString("gitproxy", null, "upstreamUser", upstreamUser);
        log.debug("Stored Bitbucket upstream username '{}' for push email '{}'", upstreamUser, pushEmail);
    }
}
