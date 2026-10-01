package site.vackstudio.vanticheat.trusted;

import java.util.List;
import java.util.UUID;

public interface TrustedPlayerService {
    TrustedPlayerService NONE = new TrustedPlayerService() {
        @Override public boolean isTrusted(UUID id) { return false; }
        @Override public boolean add(UUID id, String name) { return false; }
        @Override public boolean remove(UUID id) { return false; }
        @Override public List<TrustedPlayer> list() { return List.of(); }
        @Override public int size() { return 0; }
        @Override public void load() { }
        @Override public void save() { }
    };

    boolean isTrusted(UUID id);
    boolean add(UUID id, String name);
    boolean remove(UUID id);
    List<TrustedPlayer> list();
    default int size() { return list().size(); }
    void load();
    void save();
}
