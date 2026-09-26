package site.vackstudio.vanticheat.platform.lunar;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;

class ApolloBridgeLoaderTest {
    @Test
    void missingOptionalBridgeDoesNotPreventPluginStartup() {
        assertNull(ApolloBridgeLoader.load("missing.ApolloBridge", getClass().getClassLoader(),
                Logger.getAnonymousLogger(), (id, name) -> { }, id -> { }));
    }
}
