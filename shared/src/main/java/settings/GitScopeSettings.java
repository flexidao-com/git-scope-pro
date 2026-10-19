package settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-level settings for Git Scope plugin.
 * Settings are persisted across IDE restarts.
 */
@State(
    name = "GitScopeSettings",
    storages = @Storage("gitScopeSettings.xml")
)
public class GitScopeSettings implements PersistentStateComponent<GitScopeSettings> {
    
    /**
     * If true, plugin gutter markers render separately to the right of line numbers.
     * If false, plugin gutter markers render merged with IDE gutters (further right).
     * Default: false (merged with IDE gutter markers)
     */
    public boolean separateGutterRendering = false;

    /**
     * If true, editor tab colors reflect the active Git Scope diff (scope vs. base branch).
     * If false, the IDE's default tab coloring (diff from HEAD) is used.
     * Default: true (scope-based tab colors)
     */
    public boolean scopeFileColors = true;

    /**
     * Initial value of the "Show Untracked Files" toggle (Git Scope window toolbar) for a
     * project opened for the first time. Already-open projects keep their own toggle state;
     * changing this has no effect on them.
     * Default: false
     */
    public boolean showUntrackedFiles = false;

    /**
     * Initial value of the "Show Deleted Files" toggle (Git Scope window toolbar) for a
     * project opened for the first time. Already-open projects keep their own toggle state;
     * changing this has no effect on them.
     * Default: false
     */
    public boolean showDeletedFiles = false;

    /**
     * Initial value of the "Hide Noise" toggle (Git Scope window toolbar) for a project opened
     * for the first time. Already-open projects keep their own toggle state; changing this has
     * no effect on them.
     * Default: false
     */
    public boolean hideNoise = false;

    /**
     * If true, double-clicking a file node in the Git Scope change tree opens its scope diff
     * instead of the file. Single-click preview is unaffected.
     * Default: false
     */
    public boolean doubleClickOpensDiff = false;

    /**
     * If true, the built-in import rules take part in noise detection, before the user rules.
     * If false, only the user rules below are applied.
     * Default: true
     */
    public boolean builtInNoiseRulesEnabled = true;

    /*
     * Public so XmlSerializer reads and writes the field directly: it gives a field accessor
     * precedence over the same-named getNoiseRules/setNoiseRules pair (verified against the
     * platform's BeanBinding). Always change the rules through setNoiseRules (deep copy + version
     * bump) and read them through getNoiseRules (deep copy); loadState assigns this field through
     * XmlSerializerUtil.copyBean, bypassing the setter, so it bumps the version itself.
     */
    public List<NoiseRuleState> noiseRules = new ArrayList<>();

    /*
     * Bumped whenever the effective noise rules change. Private (with @Transient on its getter)
     * so XmlSerializer does not persist it: the noise classifier uses it to notice in-session
     * changes and rebuild its normalizer.
     */
    private long noiseRulesVersion = 0;

    public static GitScopeSettings getInstance() {
        return ApplicationManager.getApplication().getService(GitScopeSettings.class);
    }

    @Nullable
    @Override
    public GitScopeSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull GitScopeSettings state) {
        XmlSerializerUtil.copyBean(state, this);
        /*
         * copyBean assigns noiseRules through its field accessor (no setter call) and skips the
         * @Transient version, so setNoiseRules is not invoked here: bump explicitly, or a
         * Settings Sync / reload would not invalidate the noise classifier's cache.
         */
        bumpNoiseRulesVersion();
    }

    // Getters and setters
    public boolean isSeparateGutterRendering() {
        return separateGutterRendering;
    }

    public void setSeparateGutterRendering(boolean separateGutterRendering) {
        this.separateGutterRendering = separateGutterRendering;
    }

    public boolean isScopeFileColors() {
        return scopeFileColors;
    }

    public void setScopeFileColors(boolean scopeFileColors) {
        this.scopeFileColors = scopeFileColors;
    }

    public boolean isShowUntrackedFiles() {
        return showUntrackedFiles;
    }

    public void setShowUntrackedFiles(boolean showUntrackedFiles) {
        this.showUntrackedFiles = showUntrackedFiles;
    }

    public boolean isShowDeletedFiles() {
        return showDeletedFiles;
    }

    public void setShowDeletedFiles(boolean showDeletedFiles) {
        this.showDeletedFiles = showDeletedFiles;
    }

    public boolean isHideNoise() {
        return hideNoise;
    }

    public void setHideNoise(boolean hideNoise) {
        this.hideNoise = hideNoise;
    }

    public boolean isDoubleClickOpensDiff() {
        return doubleClickOpensDiff;
    }

    public void setDoubleClickOpensDiff(boolean doubleClickOpensDiff) {
        this.doubleClickOpensDiff = doubleClickOpensDiff;
    }

    public boolean isBuiltInNoiseRulesEnabled() {
        return builtInNoiseRulesEnabled;
    }

    /** Also bumps {@link #getNoiseRulesVersion()}, so the noise classifier picks the change up. */
    public void setBuiltInNoiseRulesEnabled(boolean builtInNoiseRulesEnabled) {
        this.builtInNoiseRulesEnabled = builtInNoiseRulesEnabled;
        bumpNoiseRulesVersion();
    }

    /** A deep copy of the user noise rules, in application order. */
    public @NotNull List<NoiseRuleState> getNoiseRules() {
        return copyOf(noiseRules);
    }

    /** Stores a deep copy of {@code noiseRules} and bumps {@link #getNoiseRulesVersion()}. */
    public void setNoiseRules(@NotNull List<NoiseRuleState> noiseRules) {
        this.noiseRules = copyOf(noiseRules);
        bumpNoiseRulesVersion();
    }

    /**
     * Changes whenever the effective noise rules change ({@link #setNoiseRules},
     * {@link #setBuiltInNoiseRulesEnabled} or {@link #loadState}). Not persisted.
     */
    @Transient
    public long getNoiseRulesVersion() {
        return noiseRulesVersion;
    }

    private static @NotNull List<NoiseRuleState> copyOf(@NotNull List<NoiseRuleState> rules) {
        List<NoiseRuleState> copy = new ArrayList<>(rules.size());
        for (NoiseRuleState rule : rules) {
            copy.add(rule.copy());
        }
        return copy;
    }

    private void bumpNoiseRulesVersion() {
        noiseRulesVersion++;
    }
}
