package dev.codei18n.idea;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

public final class TranslationConfigurable implements Configurable {
    private final Project project;
    private JCheckBox enabled;
    private JTextField language;

    public TranslationConfigurable(Project project) { this.project = project; }

    @Override public String getDisplayName() { return "CodeI18n"; }

    @Override public JComponent createComponent() {
        enabled = new JCheckBox("Display comment translations without modifying source files");
        language = new JTextField();
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 8));
        fields.add(enabled);
        fields.add(new JLabel("Display language (e.g. zh-CN, zh-TW, ja; blank follows .codei18n/config.json)"));
        fields.add(language);
        fields.add(new JLabel("Reads existing mappings.json translations. Generate missing translations with CodeI18n CLI."));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(fields, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override public boolean isModified() {
        if (enabled == null) return false;
        var settings = settings();
        return enabled.isSelected() != settings.isEnabled() || !language.getText().trim().equals(settings.language());
    }

    @Override public void apply() {
        settings().update(enabled.isSelected(), language.getText());
        TranslationService.getInstance(project).reload();
    }

    @Override public void reset() {
        if (enabled == null) return;
        enabled.setSelected(settings().isEnabled());
        language.setText(settings().language());
    }

    @Override public void disposeUIResources() { enabled = null; language = null; }

    private TranslationSettings settings() { return TranslationSettings.getInstance(project); }
}
