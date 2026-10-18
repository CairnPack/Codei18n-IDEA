package dev.codei18n.idea;

import com.google.gson.JsonParseException;
import com.intellij.ide.todo.TodoConfiguration;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates a debounced refresh: collect open editors (EDT), load mappings (pooled thread),
 * match comments (non-blocking read action), then show inlays and report errors (EDT).
 */
@Service(Service.Level.PROJECT)
public final class TranslationService implements Disposable {
    private static final int DEBOUNCE_MS = 350;
    private static final int MAX_DOCUMENT_LENGTH = 2 * 1024 * 1024;
    private static final String NOTIFICATION_GROUP = "CodeI18n";

    private record Target(Editor editor, Document document, Path path) {}
    /**
     * Mapping indexes for one refresh: per open source (null when unavailable), its root, the SHA-1 of its saved bytes
     * (absent while the document has unsaved changes), plus load errors per root.
     */
    private record Loaded(Map<Path, MappingIndex> bySource, Map<Path, Path> roots, Map<Path, String> hashes,
                          Map<Path, String> errors) {}
    private record Plan(Target target, long stamp, List<TranslationHint> hints) {}

    private final Project project;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final MappingRepository repository = new MappingRepository();
    private final Map<Path, String> reportedErrors = new HashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private InlayPresenter presenter; // Set once on the EDT by start(); null until then.
    private volatile boolean disposed;
    private volatile List<Path> watchedSources = List.of();

    public TranslationService(Project project) { this.project = project; }

    public static TranslationService getInstance(Project project) {
        return project.getService(TranslationService.class);
    }

    public void start() {
        ApplicationManager.getApplication().invokeLater(this::connect, ModalityState.any());
    }

    /** Re-matches open editors against the cached mappings. */
    public void refresh() {
        if (!isStale()) schedule();
    }

    /** Re-reads every mapping file, then refreshes. */
    public void reload() {
        if (isStale()) return;
        repository.invalidate();
        schedule();
    }

    private void reload(Collection<Path> roots) {
        if (isStale()) return;
        roots.forEach(repository::invalidate);
        schedule();
    }

    private void connect() {
        if (isStale() || presenter != null) return;
        presenter = new InlayPresenter(project, this::refresh);
        Disposer.register(this, presenter);
        Disposer.register(this, new MappingWatcher(project, () -> watchedSources, new MappingWatcher.Listener() {
            @Override public void rootsChanged(Set<Path> roots) { reload(roots); }
            @Override public void sourcesMoved() { refresh(); }
        }));
        var bus = project.getMessageBus().connect(this);
        bus.subscribe(EditorColorsManager.TOPIC, scheme -> restyle());
        bus.subscribe(TodoConfiguration.PROPERTY_CHANGE, event -> restyle());
        EditorFactory factory = EditorFactory.getInstance();
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(EditorFactoryEvent event) {
                if (event.getEditor().getProject() != project) return;
                presenter.attach(event.getEditor());
                refresh();
            }
            @Override public void editorReleased(EditorFactoryEvent event) {
                presenter.release(event.getEditor());
                if (event.getEditor().getProject() == project) refresh();
            }
        }, this);
        for (Editor editor : factory.getAllEditors()) if (editor.getProject() == project) presenter.attach(editor);
        refresh();
    }

    private void restyle() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!isStale() && presenter != null) presenter.restyle();
        }, ModalityState.any());
    }

    private void schedule() {
        long revision = generation.incrementAndGet();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isStale(revision)) return;
            alarm.cancelAllRequests();
            alarm.addRequest(() -> refreshNow(revision), DEBOUNCE_MS);
        }, ModalityState.any());
    }

    private void refreshNow(long revision) {
        if (isStale(revision) || presenter == null) return;
        TranslationSettings settings = TranslationSettings.getInstance(project);
        if (!settings.isEnabled() || project.getBasePath() == null) {
            watchedSources = List.of();
            presenter.clearAll();
            return;
        }
        List<Target> targets = openTargets();
        watchedSources = targets.stream().map(Target::path).distinct().toList();
        Path base = Path.of(project.getBasePath());
        String language = settings.language();
        // Disk I/O and JSON parsing are outside the read lock and outside the EDT.
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            Loaded loaded = load(targets, base, language, revision);
            if (loaded == null) return;
            ReadAction.nonBlocking(() -> plan(targets, loaded))
                    .withDocumentsCommitted(project)
                    .expireWith(this)
                    .expireWhen(() -> isStale(revision))
                    .coalesceBy(this)
                    .finishOnUiThread(ModalityState.any(), plans -> {
                        if (isStale(revision)) return;
                        apply(plans);
                        report(loaded.errors);
                    })
                    .submit(AppExecutorUtil.getAppExecutorService());
        });
    }

    private List<Target> openTargets() {
        List<Target> targets = new ArrayList<>();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() != project || editor.isDisposed() || editor.isViewer()) continue;
            var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file != null && file.isInLocalFileSystem()) {
                targets.add(new Target(editor, editor.getDocument(), Path.of(file.getPath())));
            }
        }
        return targets;
    }

    /** Resolves each source's nearest root and loads it once; null when a newer refresh superseded this one. */
    private Loaded load(List<Target> targets, Path base, String language, long revision) {
        Map<Path, MappingIndex> byRoot = new HashMap<>();
        Map<Path, MappingIndex> bySource = new HashMap<>();
        Map<Path, Path> roots = new HashMap<>();
        Map<Path, String> hashes = new HashMap<>();
        Map<Path, String> errors = new HashMap<>();
        for (Target target : targets) {
            if (isStale(revision)) return null;
            Path root = CodeI18nFiles.findRoot(target.path, base);
            if (root == null) continue;
            if (!byRoot.containsKey(root)) byRoot.put(root, loadRoot(root, language, errors));
            bySource.put(target.path, byRoot.get(root));
            roots.put(target.path, root);
            String hash = savedContentHash(target);
            if (hash != null) hashes.put(target.path, hash);
        }
        if (isStale(revision)) return null;
        repository.retain(byRoot.keySet());
        return new Loaded(bySource, roots, hashes, errors);
    }

    private MappingIndex loadRoot(Path root, String language, Map<Path, String> errors) {
        MappingWatcher.register(root);
        try {
            return repository.load(root, language);
        } catch (IOException | IllegalArgumentException | JsonParseException ex) {
            errors.put(root, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return null;
        }
    }

    /**
     * SHA-1 of the file bytes on disk when the document has no unsaved changes, else null: index.json positions are
     * only trusted for an unchanged file (docs/index-format.md §9).
     */
    private static String savedContentHash(Target target) {
        try {
            if (FileDocumentManager.getInstance().isDocumentUnsaved(target.document)) return null;
            if (Files.size(target.path) > MAX_DOCUMENT_LENGTH) return null;
            return CommentText.sha1(Files.readAllBytes(target.path));
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private List<Plan> plan(List<Target> targets, Loaded loaded) {
        List<Plan> plans = new ArrayList<>();
        for (Target target : targets) {
            ProgressManager.checkCanceled();
            MappingIndex index = loaded.bySource.get(target.path);
            var psi = PsiDocumentManager.getInstance(project).getPsiFile(target.document);
            boolean scannable = index != null && psi != null && target.document.getTextLength() <= MAX_DOCUMENT_LENGTH;
            List<TranslationHint> hints = List.of();
            if (scannable) {
                Path root = loaded.roots.get(target.path);
                String relative = root.relativize(target.path.toAbsolutePath().normalize()).toString().replace(java.io.File.separatorChar, '/');
                var context = new MappingIndex.FileContext(relative, loaded.hashes.get(target.path),
                        target.document.getImmutableCharSequence().toString());
                hints = TranslationHint.scan(psi, index, context);
            }
            plans.add(new Plan(target, target.document.getModificationStamp(), hints));
        }
        return plans;
    }

    private void apply(List<Plan> plans) {
        for (Plan plan : plans) {
            Editor editor = plan.target.editor;
            // After a newer edit, its stale hints are already gone and another refresh is scheduled.
            if (editor.isDisposed() || plan.stamp != plan.target.document.getModificationStamp()) continue;
            presenter.show(editor, plan.hints);
        }
    }

    /** Notifies once per distinct error; a root that recovers and fails again is reported again. */
    private void report(Map<Path, String> errors) {
        errors.forEach((root, message) -> {
            if (message.equals(reportedErrors.get(root))) return;
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification("Cannot load CodeI18n translations",
                            StringUtil.escapeXmlEntities(root + ": " + message), NotificationType.WARNING)
                    .notify(project);
        });
        reportedErrors.clear();
        reportedErrors.putAll(errors);
    }

    private boolean isStale() { return disposed || project.isDisposed(); }
    private boolean isStale(long revision) { return isStale() || revision != generation.get(); }

    @Override public void dispose() {
        disposed = true;
        watchedSources = List.of();
        generation.incrementAndGet();
    }
}
