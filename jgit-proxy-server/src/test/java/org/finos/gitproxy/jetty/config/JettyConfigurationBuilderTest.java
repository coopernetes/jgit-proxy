package org.finos.gitproxy.jetty.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.finos.gitproxy.approval.AutoApprovalGateway;
import org.finos.gitproxy.approval.UiApprovalGateway;
import org.finos.gitproxy.db.PushStoreFactory;
import org.junit.jupiter.api.Test;

class JettyConfigurationBuilderTest {

    // ---- buildApprovalGateway ----

    @Test
    void buildApprovalGateway_defaultConfig_returnsAutoApprovalGateway() {
        var loader = new JettyConfigurationLoader();
        var builder = new JettyConfigurationBuilder(loader);
        var pushStore = PushStoreFactory.inMemory();

        var gateway = builder.buildApprovalGateway(pushStore);

        assertInstanceOf(AutoApprovalGateway.class, gateway);
    }

    @Test
    void buildApprovalGateway_uiMode_returnsUiApprovalGateway() {
        var loader = new JettyConfigurationLoader(configWithApprovalMode("ui"));
        var builder = new JettyConfigurationBuilder(loader);
        var pushStore = PushStoreFactory.inMemory();

        var gateway = builder.buildApprovalGateway(pushStore);

        assertInstanceOf(UiApprovalGateway.class, gateway);
    }

    @Test
    void buildApprovalGateway_unknownMode_fallsBackToAuto() {
        var loader = new JettyConfigurationLoader(configWithApprovalMode("bogus"));
        var builder = new JettyConfigurationBuilder(loader);
        var pushStore = PushStoreFactory.inMemory();

        var gateway = builder.buildApprovalGateway(pushStore);

        assertInstanceOf(AutoApprovalGateway.class, gateway);
    }

    // ---- helpers ----

    private static Map<String, Object> configWithApprovalMode(String mode) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("approval-mode", mode);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("server", server);
        return root;
    }
}
