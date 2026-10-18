package dev.codei18n.idea;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Loads only on the refresh worker, never on the EDT; invalidation is lock-free and safe from any thread. */
public final class MappingRepository {
    private record Fingerprint(FileTime modified, long size) {}
    /** Metadata of both data files; config is null when absent. */
    private record DataFiles(Fingerprint mappings, Fingerprint config) {}
    private record Cached(long revision, DataFiles files, String language, MappingIndex index) {}

    private final Map<Path, Cached> cache = new ConcurrentHashMap<>();
    private final Map<Path, Long> revisions = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong();
    private final Duration retryDelay;
    private final int attempts;

    public MappingRepository() { this(Duration.ofMillis(250), 4); }

    MappingRepository(Duration retryDelay, int attempts) {
        this.retryDelay = retryDelay;
        this.attempts = attempts;
    }

    /** The parsed index for {@code root}, or null when it has no mappings.json. */
    public synchronized MappingIndex load(Path root, String language) throws IOException {
        for (int attempt = 1; ; attempt++) {
            long revision = revision(root);
            if (!Files.exists(CodeI18nFiles.mappings(root))) { cache.remove(root); return null; }
            DataFiles files = fingerprints(root);
            Cached previous = cache.get(root);
            if (previous != null && previous.revision == revision && previous.files.equals(files)
                    && previous.language.equals(language)) {
                return previous.index;
            }
            cache.remove(root); // Never keep serving an obsolete snapshot after a malformed update.
            try {
                MappingIndex index = parse(root, files, language);
                if (revision == revision(root)) cache.put(root, new Cached(revision, files, language, index));
                return index;
            } catch (CharacterCodingException | IllegalArgumentException | JsonParseException ex) {
                // The CLI truncates and rewrites in place: report only content that stays broken.
                if (attempt >= attempts || settled(root, files)) throw ex;
            }
        }
    }

    /** Drops one root, e.g. after a VFS or disk change below its .codei18n directory. */
    public void invalidate(Path root) {
        revisions.merge(root, 1L, Long::sum);
        cache.remove(root);
    }

    public void invalidate() { epoch.incrementAndGet(); cache.clear(); }

    /** Evicts roots without open sources; their files are no longer watched, so reload them on reopen. */
    public void retain(Collection<Path> roots) { cache.keySet().retainAll(roots); }

    // Both counters only grow, so the sum changes whenever either kind of invalidation happens.
    private long revision(Path root) { return epoch.get() + revisions.getOrDefault(root, 0L); }

    private static MappingIndex parse(Path root, DataFiles files, String language) throws IOException {
        String mappings = Files.readString(CodeI18nFiles.mappings(root));
        String config = files.config == null ? "{}" : Files.readString(CodeI18nFiles.config(root));
        return MappingIndex.parse(mappings, config, language);
    }

    private boolean settled(Path root, DataFiles before) throws IOException {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("CodeI18n reload interrupted");
        }
        return Files.exists(CodeI18nFiles.mappings(root)) && fingerprints(root).equals(before);
    }

    private static DataFiles fingerprints(Path root) throws IOException {
        Path config = CodeI18nFiles.config(root);
        return new DataFiles(fingerprint(CodeI18nFiles.mappings(root)), Files.exists(config) ? fingerprint(config) : null);
    }

    private static Fingerprint fingerprint(Path path) throws IOException {
        long size = Files.size(path);
        if (size > CodeI18nFiles.MAX_JSON_BYTES) throw new IOException("CodeI18n JSON exceeds 32 MiB: " + path);
        return new Fingerprint(Files.getLastModifiedTime(path), size);
    }
}
