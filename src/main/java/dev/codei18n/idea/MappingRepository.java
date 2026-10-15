package dev.codei18n.idea;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Accessed only by the refresh worker, never from the EDT. */
public final class MappingRepository {
    private record Fingerprint(FileTime modified, long size) {}
    private record Cached(long epoch, Fingerprint mappings, Fingerprint config, String language, MappingIndex index) {}
    private final Map<Path, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();

    public static Path findRoot(Path file, Path projectRoot) {
        Path base = projectRoot.toAbsolutePath().normalize();
        Path parent = file.toAbsolutePath().normalize().getParent();
        while (parent != null && parent.startsWith(base)) {
            if (Files.isDirectory(parent.resolve(".codei18n"))) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    public synchronized MappingIndex load(Path root, String language) throws IOException {
        long revision = epoch.get();
        Path mappings = root.resolve(".codei18n/mappings.json");
        Path config = root.resolve(".codei18n/config.json");
        if (!Files.exists(mappings)) { cache.remove(root); return null; }
        Fingerprint m = fingerprint(mappings);
        Fingerprint c = Files.exists(config) ? fingerprint(config) : null;
        Cached previous = cache.get(root);
        if (previous != null && previous.epoch == revision && previous.mappings.equals(m)
                && java.util.Objects.equals(previous.config, c) && previous.language.equals(language)) {
            return previous.index;
        }
        cache.remove(root); // Never keep serving an obsolete snapshot after a malformed update.
        MappingIndex index = MappingIndex.parse(Files.readString(mappings),
                c == null ? "{}" : Files.readString(config), language);
        if (revision == epoch.get()) cache.put(root, new Cached(revision, m, c, language, index));
        return index;
    }

    public void invalidate() { epoch.incrementAndGet(); cache.clear(); }

    private static Fingerprint fingerprint(Path path) throws IOException {
        long size = Files.size(path);
        if (size > 32L * 1024 * 1024) throw new IOException("CodeI18n JSON exceeds 32 MiB: " + path);
        return new Fingerprint(Files.getLastModifiedTime(path), size);
    }
}
