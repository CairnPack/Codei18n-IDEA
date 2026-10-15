package dev.codei18n.idea;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;

public final class RefreshTranslationsAction extends DumbAwareAction {
    @Override public void actionPerformed(AnActionEvent event) {
        var project = event.getProject();
        if (project != null) project.getService(TranslationService.class).requestRefresh(true);
    }
}
