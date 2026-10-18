package dev.codei18n.idea;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;

public final class RefreshTranslationsAction extends DumbAwareAction {
    @Override public void update(AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void actionPerformed(AnActionEvent event) {
        var project = event.getProject();
        if (project != null) TranslationService.getInstance(project).reload();
    }
}
