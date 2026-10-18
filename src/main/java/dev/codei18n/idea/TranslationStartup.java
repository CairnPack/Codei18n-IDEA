package dev.codei18n.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

public final class TranslationStartup implements StartupActivity.DumbAware {
    @Override public void runActivity(Project project) {
        TranslationService.getInstance(project).start();
    }
}
