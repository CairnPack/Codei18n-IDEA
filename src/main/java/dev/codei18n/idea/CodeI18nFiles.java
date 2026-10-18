package dev.codei18n.idea;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Layout of a CodeI18n root: the directory that contains {@code .codei18n/mappings.json} and {@code config.json}. */
final class CodeI18nFiles {
    static final String DIRECTORY = ".codei18n";
    static final long MAX_JSON_BYTES = 32L * 1024 * 1024;

    private CodeI18nFiles() {}

    static Path directory(Path root) { return root.resolve(DIRECTORY); }
    static Path mappings(Path root) { return directory(root).resolve("mappings.json"); }
    static Path config(Path root) { return directory(root).resolve("config.json"); }
    static Path index(Path root) { return directory(root).resolve("index.json"); }
    static List<Path> dataFiles(Path root) { return List.of(mappings(root), config(root), index(root)); }

    /** The nearest root at or above {@code file}'s directory, without leaving {@code projectRoot}; else null. */
    static Path findRoot(Path file, Path projectRoot) {
        Path base = projectRoot.toAbsolutePath().normalize();
        Path parent = file.toAbsolutePath().normalize().getParent();
        while (parent != null && parent.startsWith(base)) {
            if (Files.isDirectory(directory(parent))) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    /** The root owning a VFS path inside (or equal to) a .codei18n directory, else null. */
    static Path rootOf(String vfsPath) {
        String marker = "/" + DIRECTORY;
        for (int i = vfsPath.indexOf(marker); i >= 0; i = vfsPath.indexOf(marker, i + 1)) {
            int end = i + marker.length();
            if (end == vfsPath.length() || vfsPath.charAt(end) == '/') {
                Path directory = path(vfsPath.substring(0, end));
                return directory == null ? null : directory.getParent();
            }
        }
        return null;
    }

    static Path path(String path) {
        try { return Path.of(path); } catch (InvalidPathException ex) { return null; }
    }
}
