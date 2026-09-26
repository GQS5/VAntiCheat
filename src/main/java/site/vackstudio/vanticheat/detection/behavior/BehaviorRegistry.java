package site.vackstudio.vanticheat.detection.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class BehaviorRegistry {
    private static final int DEFAULT_WINDOW_CAPACITY = 128;
    private final List<BehaviorModule> modules = new CopyOnWriteArrayList<>();
    private final Map<UUID, PlayerBehaviorSession> sessions = new ConcurrentHashMap<>();
    private final int windowCapacity;
    private volatile BehaviorModuleContext context;
    private volatile boolean started;

    public BehaviorRegistry() { this(DEFAULT_WINDOW_CAPACITY); }

    public BehaviorRegistry(int windowCapacity) {
        if (windowCapacity < 1) throw new IllegalArgumentException("windowCapacity must be positive");
        this.windowCapacity = windowCapacity;
    }

    public void register(BehaviorModule module) {
        Objects.requireNonNull(module, "module");
        if (started) throw new IllegalStateException("Cannot register after start");
        if (modules.stream().anyMatch(existing -> existing.definition().id().equals(module.definition().id()))) {
            throw new IllegalArgumentException("Duplicate behavior module: " + module.definition().id());
        }
        modules.add(module);
    }

    public synchronized void start(BehaviorModuleContext context) {
        if (started) throw new IllegalStateException("Behavior registry has already started");
        this.context = Objects.requireNonNull(context, "context");
        try {
            for (BehaviorModule module : modules) if (module.definition().enabled()) module.initialize(context);
            started = true;
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    public void observe(BehaviorObservation observation) {
        Objects.requireNonNull(observation, "observation");
        BehaviorModuleContext currentContext = context;
        if (!started || currentContext == null) return;
        PlayerBehaviorSession session = sessions.computeIfAbsent(observation.playerId(),
                id -> new PlayerBehaviorSession(id, windowCapacity));
        session.record(observation);
        for (BehaviorModule module : modules) {
            if (module.definition().enabled()) module.observe(observation, session, currentContext);
        }
    }

    public void remove(UUID playerId) { if (playerId != null) sessions.remove(playerId); }

    public synchronized void stop() {
        if (context != null) {
            for (BehaviorModule module : modules) module.shutdown();
        }
        sessions.clear();
        started = false;
        context = null;
    }

    public int sessionCount() { return sessions.size(); }
    public List<BehaviorModule> modules() { return List.copyOf(new ArrayList<>(modules)); }
    public boolean started() { return started; }
}
