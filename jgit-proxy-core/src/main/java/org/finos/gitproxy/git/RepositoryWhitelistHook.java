package org.finos.gitproxy.git;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.finos.gitproxy.db.model.PushStep;
import org.finos.gitproxy.db.model.StepStatus;

/**
 * Pre-receive hook that records the repository whitelist check result. In store-and-forward mode, the repository is
 * validated by {@link StoreAndForwardRepositoryResolver} before the hook chain runs — if we reach this hook, the
 * repository is already whitelisted. This hook records that result so the dashboard shows parity with proxy mode's
 * {@code WhitelistAggregateFilter} step.
 */
@Slf4j
@RequiredArgsConstructor
public class RepositoryWhitelistHook implements PreReceiveHook {

    private static final int STEP_ORDER = 1000;

    private final PushContext pushContext;

    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        log.debug("Repository whitelist check: passed (resolver already validated)");
        pushContext.addStep(PushStep.builder()
                .stepName("RepositoryWhitelistHook")
                .stepOrder(STEP_ORDER)
                .status(StepStatus.PASS)
                .build());
    }
}
