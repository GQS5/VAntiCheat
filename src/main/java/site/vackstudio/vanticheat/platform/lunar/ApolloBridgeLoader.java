package site.vackstudio.vanticheat.platform.lunar;

import site.vackstudio.vanticheat.lunar.LunarClientIntegration;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads the optional Apollo bridge without linking the main plugin class to Apollo. */
public final class ApolloBridgeLoader {
    private static final String BRIDGE_CLASS =
            "site.vackstudio.vanticheat.platform.lunar.ApolloLunarBridge";

    private ApolloBridgeLoader() { }

    public static LunarClientIntegration load(Logger logger,
                                               BiConsumer<UUID, String> onRegister,
                                               Consumer<UUID> onUnregister) {
        return load(BRIDGE_CLASS, ApolloBridgeLoader.class.getClassLoader(), logger,
                onRegister, onUnregister);
    }

    static LunarClientIntegration load(String className, ClassLoader classLoader, Logger logger,
                                       BiConsumer<UUID, String> onRegister,
                                       Consumer<UUID> onUnregister) {
        try {
            Class<?> bridgeType = Class.forName(className, true, classLoader);
            Method create = bridgeType.getMethod("create", Logger.class, BiConsumer.class, Consumer.class);
            Object result = create.invoke(null, logger, onRegister, onUnregister);
            if (result instanceof Optional<?> optional && optional.orElse(null) instanceof LunarClientIntegration integration) {
                return integration;
            }
        } catch (LinkageError | ReflectiveOperationException | RuntimeException exception) {
            logger.log(Level.FINE,
                    "[VAntiCheat] Apollo integration unavailable; continuing without Lunar policy", exception);
        }
        return null;
    }
}
