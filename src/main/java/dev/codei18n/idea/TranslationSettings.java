package dev.codei18n.idea;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;

@Service(Service.Level.PROJECT)
@State(name = "CodeI18nSettings", storages = @Storage("codei18n.xml"))
public final class TranslationSettings implements PersistentStateComponent<TranslationSettings.Values> {
    /** Persisted bean; the XML serializer needs public mutable fields. Prefer the accessors below. */
    public static final class Values {
        public boolean enabled = true;
        public String language = "";
        /** A {@link DisplayMode} name; unknown or missing values read as {@link DisplayMode#BELOW}. */
        public String displayMode = DisplayMode.BELOW.name();
    }
    private Values state = new Values();

    public static TranslationSettings getInstance(Project project) {
        return project.getService(TranslationSettings.class);
    }

    public boolean isEnabled() { return state.enabled; }

    public void setEnabled(boolean enabled) { state.enabled = enabled; }

    /** Display language override; blank follows .codei18n/config.json. */
    public String language() { return state.language == null ? "" : state.language.trim(); }

    public DisplayMode displayMode() { return DisplayMode.parse(state.displayMode); }

    public void setDisplayMode(DisplayMode mode) { state.displayMode = mode.name(); }

    public void update(boolean enabled, String language) {
        state.enabled = enabled;
        state.language = language.trim();
    }

    public void update(boolean enabled, String language, DisplayMode mode) {
        update(enabled, language);
        setDisplayMode(mode);
    }

    @Override public Values getState() { return state; }
    @Override public void loadState(Values state) { this.state = state; }
}
