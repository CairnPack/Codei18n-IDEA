package dev.codei18n.idea;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Where sources find their .codei18n root: solutions in a repository subdirectory, Git work trees, and the user's home. */
final class RootLocationTest {
    private static int assertions;

    private RootLocationTest() {}

    static void run() throws Exception {
        Path layout = Files.createTempDirectory("codei18n-roots-");
        try {
            // A Rider solution in a repository subdirectory; the CLI wrote .codei18n at the repository root.
            Path repo = Files.createDirectories(layout.resolve("Project-Launcher"));
            Files.createDirectories(repo.resolve(".git"));
            Files.createDirectories(repo.resolve(".codei18n"));
            Path solution = Files.createDirectories(repo.resolve("src/backend"));
            Path source = Files.createDirectories(solution.resolve("Ops")).resolve("DevOps.cs");

            // Rider's project base path is the solution directory (the platform maps .idea/.idea.<name> back to it).
            equal(repo, CodeI18nFiles.findRoot(source, solution, null));
            equal(repo, CodeI18nFiles.findRoot(source, null, null));
            // The poll compares snapshots: it must watch the same root the refresh loads.
            equal(Set.of(), MappingSnapshot.capture(List.of(source), solution).changedRoots(
                    MappingSnapshot.capture(List.of(source), solution)));
            equal(repo, MappingSnapshot.capture(List.of(source), solution).roots().get(source).orElse(null));

            // The nearest root still wins inside the work tree.
            Path nested = Files.createDirectories(solution.resolve("Ops/.codei18n")).getParent();
            equal(nested, CodeI18nFiles.findRoot(source, solution, null));
            Files.delete(nested.resolve(".codei18n"));

            // ~/.codei18n is the CLI's global configuration, never a project root.
            equal(null, CodeI18nFiles.findRoot(source, solution, repo));
            equal(repo, CodeI18nFiles.findRoot(source, solution, solution));

            // Worktrees and submodules have a .git file.
            Path worktree = Files.createDirectories(layout.resolve("wt/feature"));
            Files.writeString(worktree.resolve(".git"), "gitdir: ../../repo/.git/worktrees/feature\n");
            Files.createDirectories(worktree.resolve(".codei18n"));
            Path module = Files.createDirectories(worktree.resolve("a/b"));
            equal(worktree, CodeI18nFiles.findRoot(module.resolve("F.kt"), module, null));
            // The nearest work tree bounds the search: the outer layout directory is never reached.
            Files.createDirectories(layout.resolve(".codei18n"));
            equal(worktree, CodeI18nFiles.findRoot(module.resolve("F.kt"), module, null));
            Files.delete(worktree.resolve(".codei18n"));
            equal(null, CodeI18nFiles.findRoot(module.resolve("F.kt"), module, null));

            // Outside Git, the project directory is the bound, as before.
            Path loose = Files.createDirectories(layout.resolve("loose"));
            Files.createDirectories(loose.resolve(".codei18n"));
            Path inner = Files.createDirectories(loose.resolve("inner"));
            equal(null, CodeI18nFiles.findRoot(inner.resolve("F.java"), inner, null));
            equal(loose, CodeI18nFiles.findRoot(inner.resolve("F.java"), loose, null));
            equal(null, CodeI18nFiles.findRoot(inner.resolve("F.java"), null, null));
            equal(null, CodeI18nFiles.findRoot(inner.resolve("F.java"), repo, null)); // A source outside the project.
        } finally {
            try (var paths = Files.walk(layout)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("PASS: " + assertions + " root location assertions");
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
