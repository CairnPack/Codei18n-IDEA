package dev.codei18n.idea;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;

/**
 * Switches between showing translations below comments and in place of them (replace mode); selected means replace
 * mode. Takes effect in open editors at once.
 */
public final class ToggleDisplayModeAction extends ToggleAction implements DumbAware {
    @Override public boolean isSelected(AnActionEvent event) {
        var project = event.getProject();
        return project != null && TranslationSettings.getInstance(project).displayMode() == DisplayMode.REPLACE;
    }

    @Override public void setSelected(AnActionEvent event, boolean state) {
        var project = event.getProject();
        if (project != null) {
            TranslationService.getInstance(project).setDisplayMode(state ? DisplayMode.REPLACE : DisplayMode.BELOW);
        }
    }

    @Override public void update(AnActionEvent event) {
        super.update(event);
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
