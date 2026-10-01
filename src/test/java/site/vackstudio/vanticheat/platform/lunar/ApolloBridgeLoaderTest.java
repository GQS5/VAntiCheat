package site.vackstudio.vanticheat.platform.lunar;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApolloBridgeLoaderTest {
    @Test
    void missingOptionalBridgeDoesNotPreventPluginStartup() {
        ClassLoader withoutApollo = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.lunarclient.apollo.Apollo")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        assertNull(ApolloBridgeLoader.load("site.vackstudio.vanticheat.platform.lunar.ApolloLunarBridge", withoutApollo,
                Logger.getAnonymousLogger(), registration -> { }, registration -> { }));
    }

    @Test
    void presentApiWithMissingBridgeIsReportedAsFailedCompatibility() {
        var integration = ApolloBridgeLoader.load("missing.ApolloBridge", getClass().getClassLoader(),
                Logger.getAnonymousLogger(), registration -> { }, registration -> { });
        assertNotNull(integration);
        assertEquals(site.vackstudio.vanticheat.lunar.LunarClientIntegration.Availability.FAILED,
                integration.availability());
    }

    @Test
    void presentApiBeforeApolloInitializationIsNotReadyRatherThanAbsent() {
        var integration = ApolloBridgeLoader.load(Logger.getAnonymousLogger(), registration -> { }, registration -> { });
        assertNotNull(integration);

        integration.start();
        try {
            assertEquals(site.vackstudio.vanticheat.lunar.LunarClientIntegration.Availability.NOT_READY,
                    integration.availability());
        } finally {
            integration.stop();
        }
    }
}
