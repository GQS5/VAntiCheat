package site.vackstudio.vanticheat.detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DetectionRegistry {
    private enum State { OPEN, INITIALIZED, STARTED, DISABLED, STOPPED }

    private final ConcurrentMap<String, DetectionModule> modules = new ConcurrentHashMap<>();
    private volatile State state = State.OPEN;

    public synchronized void register(DetectionModule module) {
        Objects.requireNonNull(module, "module");
        ensureState(State.OPEN);
        DetectionDefinition definition = Objects.requireNonNull(module.definition(), "definition");
        if (!definition.enabled()) throw new IllegalArgumentException("Detection is disabled");
        if (module.version() == null || module.version().isBlank()) {
            throw new IllegalArgumentException("Module version cannot be blank");
        }
        if (modules.putIfAbsent(definition.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate detection id: " + definition.id());
        }
    }

    public synchronized DetectionModule unregister(String id) {
        ensureState(State.OPEN);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Detection id cannot be blank");
        return modules.remove(id);
    }

    public DetectionModule lookup(String id) {
        return id == null ? null : modules.get(id);
    }

    public List<DetectionModule> activeModules() {
        if (state == State.DISABLED || state == State.STOPPED) return List.of();
        return List.copyOf(modules.values());
    }

    public synchronized void initializeAll(DetectionModuleContext context) {
        ensureState(State.OPEN);
        Objects.requireNonNull(context, "context");
        List<DetectionModule> initialized = new ArrayList<>();
        try {
            for (DetectionModule module : modules.values()) {
                module.initialize(context);
                initialized.add(module);
            }
            state = State.INITIALIZED;
        } catch (RuntimeException failure) {
            stopReverse(initialized);
            state = State.STOPPED;
            throw failure;
        }
    }

    public synchronized void startAll() {
        ensureState(State.INITIALIZED);
        List<DetectionModule> started = new ArrayList<>();
        try {
            for (DetectionModule module : modules.values()) {
                module.start();
                started.add(module);
            }
            state = State.STARTED;
        } catch (RuntimeException failure) {
            stopReverse(started);
            state = State.STOPPED;
            throw failure;
        }
    }

    public synchronized void disable() {
        ensureState(State.OPEN);
        state = State.DISABLED;
    }

    public synchronized void stopAll() {
        if (state == State.STOPPED) return;
        if (state == State.INITIALIZED || state == State.STARTED) {
            stopReverse(new ArrayList<>(modules.values()));
        }
        state = State.STOPPED;
    }

    private void stopReverse(List<DetectionModule> values) {
        for (int i = values.size() - 1; i >= 0; i--) {
            try {
                values.get(i).stop();
            } catch (RuntimeException ignored) {
                // Continue cleanup for the remaining modules.
            }
        }
    }

    private void ensureState(State expected) {
        if (state != expected) throw new IllegalStateException("Registry is " + state);
    }
}
