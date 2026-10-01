package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import site.vackstudio.vanticheat.platform.ClientPlatformService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Optional official Floodgate/Geyser API adapters, isolated from plugin class loading. */
public final class PaperClientPlatformProviders {
    private PaperClientPlatformProviders() { }

    public static ClientPlatformService create() {
        return new ClientPlatformService(new FloodgateProvider(), new GeyserProvider());
    }

    private abstract static class ReflectiveProvider implements ClientPlatformService.Provider {
        private final String pluginName;
        private final String apiClassName;
        private final String accessor;
        private volatile Object api;
        private volatile Plugin plugin;
        private volatile boolean pluginLookupComplete;
        private volatile Class<?> apiType;
        private volatile Method getter;
        private volatile boolean apiLookupComplete;
        private volatile boolean failed;
        private final Map<String, Method> methods = new ConcurrentHashMap<>();

        private ReflectiveProvider(String pluginName, String apiClassName, String accessor) {
            this.pluginName = pluginName;
            this.apiClassName = apiClassName;
            this.accessor = accessor;
        }

        @Override public boolean installed() { return findPlugin() != null; }

        @Override public boolean ready() { return api() != null; }

        @Override public ClientPlatformService.ProviderState status() {
            if (failed) return ClientPlatformService.ProviderState.FAILED;
            if (findPlugin() == null) return ClientPlatformService.ProviderState.NOT_PRESENT;
            return api() == null ? (failed ? ClientPlatformService.ProviderState.FAILED
                    : ClientPlatformService.ProviderState.NOT_READY) : ClientPlatformService.ProviderState.READY;
        }

        protected Object api() {
            Object cached = api;
            if (cached != null) return cached;
            Plugin plugin = findPlugin();
            if (plugin == null) return null;
            synchronized (this) {
                if (api != null) return api;
                try {
                    if (!apiLookupComplete) {
                        apiType = Class.forName(apiClassName, true, plugin.getClass().getClassLoader());
                        getter = apiType.getMethod(accessor);
                        apiLookupComplete = true;
                    }
                    if (getter == null) return null;
                    api = getter.invoke(null);
                } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                         | LinkageError | RuntimeException exception) {
                    apiLookupComplete = true;
                    failed = true;
                    return null;
                } catch (InvocationTargetException exception) {
                    if (!(exception.getCause() instanceof UnsupportedOperationException)) failed = true;
                    return null;
                }
                return api;
            }
        }

        protected Object invoke(String methodName, UUID playerId) {
            Object instance = api();
            if (instance == null) return null;
            try {
                Method method = methods.get(methodName);
                if (method == null) {
                    synchronized (this) {
                        method = methods.get(methodName);
                        if (method == null) {
                            method = instance.getClass().getMethod(methodName, UUID.class);
                            methods.put(methodName, method);
                        }
                    }
                }
                return method.invoke(instance, playerId);
            } catch (NoSuchMethodException | IllegalAccessException | LinkageError | RuntimeException exception) {
                failed = true;
                return null;
            } catch (InvocationTargetException exception) {
                failed = true;
                return null;
            }
        }

        private Plugin findPlugin() {
            if (pluginLookupComplete) return plugin;
            synchronized (this) {
                if (pluginLookupComplete) return plugin;
                try {
                    plugin = Bukkit.getPluginManager().getPlugin(pluginName);
                    if (plugin == null && pluginName.equals("floodgate")) {
                        plugin = Bukkit.getPluginManager().getPlugin("Floodgate");
                    }
                    if (plugin == null && pluginName.equals("Geyser-Spigot")) {
                        plugin = Bukkit.getPluginManager().getPlugin("Geyser");
                    }
                } catch (RuntimeException exception) {
                    failed = true;
                }
                pluginLookupComplete = true;
            }
            return plugin;
        }
    }

    private static final class FloodgateProvider extends ReflectiveProvider {
        private FloodgateProvider() {
            super("floodgate", "org.geysermc.floodgate.api.FloodgateApi", "getInstance");
        }

        @Override public String name() { return "FLOODGATE"; }

        @Override public Boolean isBedrock(UUID playerId) {
            Object answer = invoke("isFloodgatePlayer", playerId);
            return answer instanceof Boolean value ? value : null;
        }
    }

    private static final class GeyserProvider extends ReflectiveProvider {
        private GeyserProvider() {
            super("Geyser-Spigot", "org.geysermc.geyser.api.GeyserApi", "api");
        }

        @Override public String name() { return "GEYSER"; }

        @Override public Boolean isBedrock(UUID playerId) {
            Object answer = invoke("isBedrockPlayer", playerId);
            Object connection = invoke("connectionByUuid", playerId);
            if (connection instanceof java.util.Optional<?> optional && optional.isPresent()) return true;
            if (connection != null && !(connection instanceof java.util.Optional<?>)) return true;
            return answer instanceof Boolean value ? value : null;
        }
    }
}
