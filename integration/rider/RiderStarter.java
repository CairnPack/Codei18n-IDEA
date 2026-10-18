package dev.codei18n.integration;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import dev.codei18n.idea.DisplayMode;
import dev.codei18n.idea.LanguageSamplesTest;
import dev.codei18n.idea.TranslationService;
import dev.codei18n.idea.TranslationSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * test-rider.ps1: the C# sample in JetBrains Rider, whose frontend has no comment PSI for C#, so the plugin can only
 * match through the index.json ranges and textHash. Depends on the platform and the main plugin only.
 */
public final class RiderStarter implements ApplicationStarter {
    @Override public boolean isHeadless() { return true; }

    @Override public int getRequiredModality() { return NOT_IN_EDT; }

    @Override public void main(List<String> args) {
        try {
            Path samples = Path.of(System.getProperty("codei18n.rider.project"));
            Path png = Path.of(System.getProperty("codei18n.rider.screenshot"));
            var build = ApplicationInfo.getInstance().getBuild();
            System.out.println("IDE " + build.getProductCode() + "-" + build.asString() + " ("
                    + ApplicationInfo.getInstance().getFullApplicationName() + ")");
            long started = System.nanoTime();
            Project project = ProjectManagerEx.getInstanceEx().openProject(samples, OpenProjectTask.build());
            if (project == null) throw new AssertionError("isolated sample project did not open");
            System.out.println("PASS: isolated sample project opened (" + project.getClass().getName() + ", "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "ms)");
            ApplicationManager.getApplication().invokeAndWait(() -> {
                TranslationSettings settings = TranslationSettings.getInstance(project);
                settings.update(true, "", DisplayMode.BELOW);
                TranslationService.getInstance(project).reload();
            });
            List<String> latencies = LanguageSamplesTest.runSingle(project, samples, "Ledger.cs", png);
            System.out.println("LATENCIES " + String.join("; ", latencies));
            Files.writeString(Path.of(System.getProperty("codei18n.rider.result")),
                    "PASS: " + build.getProductCode() + "-" + build.asString() + " C# Ledger.cs via the no-PSI index.json fallback:"
                            + " block, merged line group and doc comment translations (exact inlay text), no inlay for // in a string,"
                            + " external mapping rewrite, document and file bytes unchanged, screenshot " + png.getFileName()
                            + "; " + String.join("; ", latencies) + "\n");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
