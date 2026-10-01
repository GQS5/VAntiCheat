package site.vackstudio.vanticheat.platform.lunar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApolloPluginLifecycleListenerTest {
    @Test
    void recognizesOptionalApolloPluginNamesCaseInsensitively() {
        assertTrue(ApolloPluginLifecycleListener.isApollo("Apollo"));
        assertTrue(ApolloPluginLifecycleListener.isApollo("Apollo-Bukkit"));
        assertTrue(ApolloPluginLifecycleListener.isApollo("Apollo-Folia"));
        assertFalse(ApolloPluginLifecycleListener.isApollo("VAntiCheat"));
        assertFalse(ApolloPluginLifecycleListener.isApollo(null));
    }
}
