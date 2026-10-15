package dev.codei18n.idea;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;

@Service(Service.Level.PROJECT)
@State(name = "CodeI18nSettings", storages = @Storage("codei18n.xml"))
public final class TranslationSettings implements PersistentStateComponent<TranslationSettings.Values> {
    public static final class Values {
        public boolean enabled = true;
        public String language = "";
    }
    private Values state = new Values();
    @Override public Values getState() { return state; }
    @Override public void loadState(Values state) { this.state = state; }
}
