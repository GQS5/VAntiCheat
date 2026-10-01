package site.vackstudio.vanticheat.platform.lunar;

import site.vackstudio.vanticheat.lunar.LunarClientIntegration;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads the optional Apollo bridge without linking the main plugin class to Apollo. */
public final class ApolloBridgeLoader {
    private static final String BRIDGE_CLASS =
            "site.vackstudio.vanticheat.platform.lunar.ApolloLunarBridge";
    private static final String APOLLO_API_CLASS = "com.lunarclient.apollo.Apollo";

    private ApolloBridgeLoader() { }

    public static LunarClientIntegration load(Logger logger,
                                               Consumer<LunarClientIntegration.Registration> onRegister,
                                               Consumer<LunarClientIntegration.Registration> onUnregister) {
        return load(BRIDGE_CLASS, ApolloBridgeLoader.class.getClassLoader(), logger,
                onRegister, onUnregister);
    }

    static LunarClientIntegration load(String className, ClassLoader classLoader, Logger logger,
                                       Consumer<LunarClientIntegration.Registration> onRegister,
                                       Consumer<LunarClientIntegration.Registration> onUnregister) {
        try {
            Class.forName(APOLLO_API_CLASS, false, classLoader);
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (LinkageError | RuntimeException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo API could not be resolved", exception);
            return new FailedIntegration();
        }
        try {
            Class<?> bridgeType = Class.forName(className, true, classLoader);
            Method create = bridgeType.getMethod("create", Logger.class, Consumer.class, Consumer.class);
            Object result = create.invoke(null, logger, onRegister, onUnregister);
            if (result instanceof Optional<?> optional && optional.orElse(null) instanceof LunarClientIntegration integration) {
                return integration;
            }
            logger.fine("[VAntiCheat] Apollo API is present but the Lunar bridge could not be created");
        } catch (LinkageError | ReflectiveOperationException | RuntimeException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo bridge compatibility failure", exception);
        }
        return new FailedIntegration();
    }

    private static final class FailedIntegration implements LunarClientIntegration {
        @Override public Availability availability() { return Availability.FAILED; }
        @Override public boolean hasSupport(UUID playerId) { return false; }
        @Override public MinimapAction disableMinimap(UUID playerId, Object playerHandle) {
            return MinimapAction.FAILED;
        }
        @Override public Optional<Boolean> minimapStatus(UUID playerId) { return Optional.empty(); }
        @Override public void start() { }
        @Override public void stop() { }
    }
}
