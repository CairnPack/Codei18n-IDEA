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

    /**
     * The nearest root at or above {@code file}'s directory, else null. The search stays within the project directory
     * or, when wider, the Git work tree around the file: the CLI runs at the work tree root, so a solution or module
     * opened in a subdirectory still finds that .codei18n. The user's home is skipped: {@code ~/.codei18n} holds the
     * CLI's global configuration, not a project's data.
     */
    static Path findRoot(Path file, Path projectDirectory) {
        return findRoot(file, projectDirectory, path(System.getProperty("user.home", "")));
    }

    static Path findRoot(Path file, Path projectDirectory, Path home) {
        Path start = file.toAbsolutePath().normalize().getParent();
        if (start == null) return null;
        Path project = projectDirectory == null ? null : projectDirectory.toAbsolutePath().normalize();
        Path bound = project != null && start.startsWith(project) ? project : null;
        Path workTree = workTree(start);
        if (workTree != null && (bound == null || bound.startsWith(workTree))) bound = workTree;
        if (bound == null) return null;
        Path skipped = home == null || home.toString().isEmpty() ? null : home.toAbsolutePath().normalize();
        for (Path parent = start; parent != null && parent.startsWith(bound); parent = parent.getParent()) {
            if (!parent.equals(skipped) && Files.isDirectory(directory(parent))) return parent;
        }
        return null;
    }

    /** The nearest directory at or above {@code start} with a .git entry: a directory, or a file in worktrees and submodules. */
    private static Path workTree(Path start) {
        for (Path parent = start; parent != null; parent = parent.getParent()) {
            if (Files.exists(parent.resolve(".git"))) return parent;
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
