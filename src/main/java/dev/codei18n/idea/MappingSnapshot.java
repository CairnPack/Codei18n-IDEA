package dev.codei18n.idea;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Cheap disk metadata check, independent of IDEA's VFS refresh cycle. */
record MappingSnapshot(Map<Path, Optional<Path>> roots, Map<Path, FileState> files) {
    private record FileState(Path root, FileTime modified, FileTime created, long size, Object key, String error) {}

    static MappingSnapshot capture(List<Path> sources, Path base) {
        Map<Path, Optional<Path>> roots = new HashMap<>();
        for (Path source : sources) roots.put(source, Optional.ofNullable(CodeI18nFiles.findRoot(source, base)));
        Map<Path, FileState> files = new HashMap<>();
        for (Optional<Path> root : new HashSet<>(roots.values())) {
            if (root.isEmpty()) continue;
            for (Path file : CodeI18nFiles.dataFiles(root.get())) files.put(file, state(root.get(), file));
        }
        return new MappingSnapshot(Map.copyOf(roots), Map.copyOf(files));
    }

    /**
     * Roots whose mapping data changed since {@code previous}. Opening or closing sources alone is not a change:
     * only sources and mapping files observed in both snapshots are compared.
     */
    Set<Path> changedRoots(MappingSnapshot previous) {
        Set<Path> changed = new HashSet<>();
        if (previous == null) return changed;
        roots.forEach((source, root) -> {
            Optional<Path> before = previous.roots.get(source);
            if (before != null && !before.equals(root)) {
                // A .codei18n directory appeared, disappeared or moved for an open source.
                before.ifPresent(changed::add);
                root.ifPresent(changed::add);
            }
        });
        files.forEach((file, state) -> {
            FileState before = previous.files.get(file);
            if (before != null && !before.equals(state)) changed.add(state.root());
        });
        return changed;
    }

    private static FileState state(Path root, Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            // Windows can return a null fileKey; creation time also detects replacement files.
            return new FileState(root, attributes.lastModifiedTime(), attributes.creationTime(),
                    attributes.size(), attributes.fileKey(), null);
        } catch (NoSuchFileException ex) {
            return new FileState(root, null, null, 0, null, "missing");
        } catch (IOException | SecurityException ex) {
            // Keep checking after temporary read failures, including recovery to the old metadata.
            return new FileState(root, null, null, 0, null, ex.getClass().getName() + ": " + ex.getMessage());
        }
    }
}
