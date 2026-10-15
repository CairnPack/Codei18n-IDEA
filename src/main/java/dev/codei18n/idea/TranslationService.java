package dev.codei18n.idea;

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
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.VisibleAreaEvent;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.PROJECT)
public final class TranslationService implements Disposable {
    private record Target(Editor editor, Document document, Path path) {}
    private record Hint(int offset, String text, String language) {}
    private record Plan(Target target, long stamp, List<Hint> hints) {}
    private record Result(List<Plan> plans, Map<Path, String> errors) {}
    private final Project project;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final MappingRepository repository = new MappingRepository();
    private final Map<Editor, List<Inlay<?>>> inlays = new HashMap<>();
    private final Map<Path, String> reportedErrors = new HashMap<>();
    private final Set<Editor> attachedEditors = new HashSet<>();
    private final AtomicLong generation = new AtomicLong();
    private boolean started;
    private volatile boolean disposed;

    public TranslationService(Project project) { this.project = project; }

    public void start() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed() || started) return;
            started = true;
            EditorFactory factory = EditorFactory.getInstance();
            factory.getEventMulticaster().addDocumentListener(new DocumentListener() {
                @Override public void documentChanged(DocumentEvent event) {
                    if (Arrays.stream(factory.getEditors(event.getDocument(), project)).findAny().isPresent()) {
                        for (Editor editor : factory.getEditors(event.getDocument(), project)) clear(editor);
                        requestRefresh(false);
                    }
                }
            }, this);
            factory.addEditorFactoryListener(new EditorFactoryListener() {
                @Override public void editorCreated(EditorFactoryEvent event) {
                    if (event.getEditor().getProject() == project) {
                        attach(event.getEditor());
                        requestRefresh(false);
                    }
                }
                @Override public void editorReleased(EditorFactoryEvent event) {
                    clear(event.getEditor());
                    attachedEditors.remove(event.getEditor());
                }
            }, this);
            project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override public void after(List<? extends VFileEvent> events) {
                    // Include directory rename/removal and source moves (nested root may change).
                    if (!events.isEmpty()) requestRefresh(true);
                }
            });
            for (Editor editor : factory.getAllEditors()) if (editor.getProject() == project) attach(editor);
            requestRefresh(false);
        }, ModalityState.any());
    }

    private void attach(Editor editor) {
        if (!attachedEditors.add(editor)) return;
        editor.getScrollingModel().addVisibleAreaListener(new VisibleAreaListener() {
            @Override public void visibleAreaChanged(VisibleAreaEvent event) {
                if (event.getOldRectangle() == null
                        || event.getOldRectangle().width != event.getNewRectangle().width) {
                    for (Inlay<?> inlay : inlays.getOrDefault(editor, List.of())) {
                        if (inlay.isValid()) inlay.update();
                    }
                }
            }
        }, this);
    }

    public void requestRefresh(boolean invalidate) {
        if (invalidate) repository.invalidate();
        long revision = generation.incrementAndGet();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed() || revision != generation.get()) return;
            alarm.cancelAllRequests();
            alarm.addRequest(() -> refresh(revision), 350);
        }, ModalityState.any());
    }

    private void refresh(long revision) {
        if (disposed || project.isDisposed()) return;
        TranslationSettings.Values settings = project.getService(TranslationSettings.class).getState();
        if (!settings.enabled || project.getBasePath() == null) { clearAll(); return; }
        String language = settings.language == null ? "" : settings.language;
        Path base = Path.of(project.getBasePath());
        List<Target> targets = new ArrayList<>();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() != project || editor.isDisposed() || editor.isViewer()) continue;
            var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file != null && file.isInLocalFileSystem()) {
                targets.add(new Target(editor, editor.getDocument(), Path.of(file.getPath())));
            }
        }
        // Disk I/O and JSON parsing are outside the read lock and outside the EDT.
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (disposed || revision != generation.get()) return;
            Map<Path, MappingIndex> indexes = new HashMap<>();
            Map<Path, String> errors = new HashMap<>();
            Set<Path> loaded = new HashSet<>();
            Map<Path, MappingIndex> byFile = new HashMap<>();
            for (Target target : targets) {
                if (disposed || revision != generation.get()) return;
                Path root = MappingRepository.findRoot(target.path, base);
                if (root == null) continue;
                if (loaded.add(root)) {
                    // NIO reads alone do not register files with IDEA's VFS. Register the
                    // cache directory/files so external CLI edits produce VFS change events.
                    var local = LocalFileSystem.getInstance();
                    var directory = local.refreshAndFindFileByNioFile(root.resolve(".codei18n"));
                    if (directory != null) directory.getChildren();
                    local.refreshAndFindFileByNioFile(root.resolve(".codei18n/mappings.json"));
                    local.refreshAndFindFileByNioFile(root.resolve(".codei18n/config.json"));
                    try { indexes.put(root, repository.load(root, language)); }
                    catch (java.io.IOException | IllegalArgumentException | com.google.gson.JsonParseException ex) {
                        errors.put(root, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    }
                }
                byFile.put(target.path, indexes.get(root));
            }
            if (disposed || revision != generation.get()) return;
            ReadAction.nonBlocking(() -> {
                List<Plan> plans = new ArrayList<>();
                for (Target target : targets) {
                    ProgressManager.checkCanceled();
                    List<Hint> hints = new ArrayList<>();
                    MappingIndex index = byFile.get(target.path);
                    var psi = PsiDocumentManager.getInstance(project).getPsiFile(target.document);
                    if (index != null && psi != null && target.document.getTextLength() <= 2 * 1024 * 1024) {
                        psi.accept(new PsiRecursiveElementWalkingVisitor() {
                            @Override public void visitElement(PsiElement element) {
                                ProgressManager.checkCanceled();
                                if (element instanceof PsiComment comment) {
                                    String translated = index.translation(comment.getText());
                                    if (translated != null) hints.add(new Hint(comment.getTextRange().getEndOffset(),
                                            translated, index.language()));
                                } else super.visitElement(element);
                            }
                        });
                    }
                    plans.add(new Plan(target, target.document.getModificationStamp(), hints));
                }
                return new Result(plans, errors);
            }).withDocumentsCommitted(project).expireWith(this)
                    .expireWhen(() -> disposed || revision != generation.get())
                    .coalesceBy(this).finishOnUiThread(ModalityState.any(), result -> {
                        if (disposed || revision != generation.get()) return;
                        for (Plan plan : result.plans) {
                            Editor editor = plan.target.editor;
                            if (editor.isDisposed() || plan.stamp != plan.target.document.getModificationStamp()) continue;
                            clear(editor);
                            List<Inlay<?>> created = new ArrayList<>();
                            editor.getInlayModel().execute(true, () -> {
                                for (Hint hint : plan.hints) {
                                    Inlay<?> inlay = editor.getInlayModel().addBlockElement(hint.offset, true, false, 0,
                                            new TranslationRenderer(hint.text, hint.language));
                                    if (inlay != null) created.add(inlay);
                                }
                            });
                            inlays.put(editor, created);
                        }
                        result.errors.forEach((root, message) -> {
                            if (!message.equals(reportedErrors.get(root))) {
                                NotificationGroupManager.getInstance().getNotificationGroup("CodeI18n")
                                        .createNotification("Cannot load CodeI18n translations",
                                                com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(root + ": " + message),
                                                NotificationType.WARNING).notify(project);
                            }
                        });
                        reportedErrors.clear();
                        reportedErrors.putAll(result.errors);
                    }).submit(AppExecutorUtil.getAppExecutorService());
        });
    }

    private void clear(Editor editor) {
        List<Inlay<?>> previous = inlays.remove(editor);
        if (previous != null) for (Inlay<?> inlay : previous) if (inlay.isValid()) Disposer.dispose(inlay);
    }
    private void clearAll() { for (Editor editor : List.copyOf(inlays.keySet())) clear(editor); }

    @Override public void dispose() {
        disposed = true;
        generation.incrementAndGet();
        if (ApplicationManager.getApplication().isDispatchThread()) clearAll();
        else ApplicationManager.getApplication().invokeLater(this::clearAll, ModalityState.any());
        attachedEditors.clear();
    }
}
