package site.vackstudio.vanticheat.trusted;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PersistentTrustedPlayerService implements TrustedPlayerService {
    private final Path file;
    private final Logger logger;
    private final Map<UUID, TrustedPlayer> players = new ConcurrentHashMap<>();

    public PersistentTrustedPlayerService(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override public boolean isTrusted(UUID id) { return id != null && players.containsKey(id); }

    @Override
    public synchronized boolean add(UUID id, String name) {
        TrustedPlayer current = players.get(id);
        if (current != null) {
            if (!current.name().equals(name)) players.put(id, new TrustedPlayer(id, name, current.addedAt()));
            return false;
        }
        players.put(id, new TrustedPlayer(id, name, Instant.now()));
        return true;
    }

    @Override public synchronized boolean remove(UUID id) { return players.remove(id) != null; }

    @Override
    public List<TrustedPlayer> list() {
        return players.values().stream()
                .sorted(Comparator.comparing(TrustedPlayer::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(value -> value.id().toString()))
                .toList();
    }

    @Override
    public synchronized void load() {
        players.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Unable to load trusted-players.yml; starting with an empty list", exception);
        }
    }

    @Override
    public synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, yaml(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Unable to save trusted-players.yml", exception);
        }
    }

    private void parse(List<String> lines) {
        UUID id = null;
        String name = null;
        Instant addedAt = null;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#") || line.trim().equals("trusted-players:")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 2 && trimmed.endsWith(":")) {
                store(id, name, addedAt);
                id = parseUuid(trimmed.substring(0, trimmed.length() - 1));
                name = null;
                addedAt = null;
            } else if (indent >= 4 && id != null) {
                String[] pair = trimmed.split(":", 2);
                if (pair.length != 2) continue;
                if (pair[0].trim().equals("name")) name = pair[1].trim();
                if (pair[0].trim().equals("added-at")) {
                    try { addedAt = Instant.parse(pair[1].trim()); }
                    catch (RuntimeException ignored) { }
                }
            }
        }
        store(id, name, addedAt);
    }

    private void store(UUID id, String name, Instant addedAt) {
        if (id == null || name == null || name.isBlank()) return;
        players.put(id, new TrustedPlayer(id, name, addedAt == null ? Instant.EPOCH : addedAt));
    }

    private UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) {
            logger.warning("Ignoring malformed trusted player UUID: " + value);
            return null;
        }
    }

    private String yaml() {
        StringBuilder output = new StringBuilder("trusted-players:\n");
        for (TrustedPlayer player : list()) {
            output.append("  ").append(player.id()).append(":\n")
                    .append("    name: ").append(player.name()).append('\n')
                    .append("    added-at: ").append(player.addedAt()).append('\n');
        }
        return output.toString();
    }
}
