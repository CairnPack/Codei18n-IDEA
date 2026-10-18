package dev.codei18n.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Detects changes to the .codei18n data behind open sources: VFS events when IDEA sees them, plus a cheap
 * metadata poll for external CLI writes that IDEA has not refreshed yet.
 */
final class MappingWatcher implements Disposable {
    interface Listener {
        /** Mapping or config data below these roots changed, or a root appeared or vanished. */
        void rootsChanged(Set<Path> roots);
        /** An open source was moved, renamed or deleted, so its nearest root may differ. */
        void sourcesMoved();
    }

    private final Supplier<List<Path>> sources;
    private final Listener listener;
    private volatile boolean disposed;
    private ScheduledFuture<?> poll;

    MappingWatcher(Project project, Supplier<List<Path>> sources, Listener listener) {
        this.sources = sources;
        this.listener = listener;
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(List<? extends VFileEvent> events) { vfsChanged(events); }
        });
        if (project.getBasePath() != null) poll(Path.of(project.getBasePath()));
    }

    /** Makes IDEA track the root's data files, so later external writes also arrive as VFS events. */
    static void register(Path root) {
        var local = LocalFileSystem.getInstance();
        var directory = local.refreshAndFindFileByNioFile(CodeI18nFiles.directory(root));
        if (directory != null) directory.getChildren();
        for (Path file : CodeI18nFiles.dataFiles(root)) local.refreshAndFindFileByNioFile(file);
    }

    private void poll(Path base) {
        poll = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(new Runnable() {
                    private MappingSnapshot previous;

                    @Override public void run() {
                        if (disposed) return;
                        MappingSnapshot current = MappingSnapshot.capture(sources.get(), base);
                        Set<Path> changed = current.changedRoots(previous);
                        previous = current;
                        if (!changed.isEmpty()) listener.rootsChanged(changed);
                    }
                }, 1, 1, TimeUnit.SECONDS);
    }

    private void vfsChanged(List<? extends VFileEvent> events) {
        Set<Path> roots = new HashSet<>();
        boolean sourcesMoved = false;
        List<Path> open = sources.get();
        for (VFileEvent event : events) {
            if (!(event.getFileSystem() instanceof LocalFileSystem)) continue;
            for (String path : paths(event)) {
                Path root = CodeI18nFiles.rootOf(path);
                if (root != null) roots.add(root);
                else if (isStructural(event) && containsAny(path, open)) sourcesMoved = true;
            }
        }
        // Saves and build output elsewhere are ignored; the nearest .codei18n may change only through these.
        if (!roots.isEmpty()) listener.rootsChanged(roots);
        else if (sourcesMoved) listener.sourcesMoved();
    }

    private static boolean isStructural(VFileEvent event) {
        return event instanceof VFileMoveEvent || event instanceof VFileDeleteEvent
                || event instanceof VFilePropertyChangeEvent change && change.isRename();
    }

    private static List<String> paths(VFileEvent event) {
        if (event instanceof VFileMoveEvent move) return List.of(move.getOldPath(), move.getNewPath());
        if (event instanceof VFilePropertyChangeEvent change && change.isRename()) {
            return List.of(change.getOldPath(), change.getNewPath());
        }
        return List.of(event.getPath());
    }

    /** Whether {@code path} is one of the sources or a directory containing one. */
    private static boolean containsAny(String path, List<Path> sources) {
        Path changed = CodeI18nFiles.path(path);
        return changed != null && sources.stream().anyMatch(source -> source.startsWith(changed));
    }

    @Override public void dispose() {
        disposed = true;
        if (poll != null) poll.cancel(false);
    }
}
