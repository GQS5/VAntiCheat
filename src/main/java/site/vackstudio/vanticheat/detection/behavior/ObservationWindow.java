package site.vackstudio.vanticheat.detection.behavior;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ObservationWindow {
    private final int capacity;
    private final Deque<BehaviorObservation> values = new ArrayDeque<>();

    public ObservationWindow(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void add(BehaviorObservation observation) {
        values.addLast(observation);
        while (values.size() > capacity) values.removeFirst();
    }

    public synchronized List<BehaviorObservation> snapshot() {
        return List.copyOf(new ArrayList<>(values));
    }

    public int capacity() { return capacity; }
    public synchronized int size() { return values.size(); }
}
