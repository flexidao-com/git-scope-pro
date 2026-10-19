package settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.ProjectManager;
import implementation.noise.OpenDiffsRefresher;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import service.ViewService;

import javax.swing.*;
import java.util.List;

/**
 * Configurable implementation for Git Scope settings.
 * This creates the settings page in IntelliJ's Settings dialog.
 */
public class GitScopeSettingsConfigurable implements Configurable {
    private GitScopeSettingsComponent settingsComponent;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Git Scope";
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return settingsComponent.getPreferredFocusedComponent();
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settingsComponent = new GitScopeSettingsComponent();
        return settingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        GitScopeSettings settings = GitScopeSettings.getInstance();
        return settingsComponent.isSeparateGutterRendering() != settings.isSeparateGutterRendering()
            || settingsComponent.isScopeFileColors() != settings.isScopeFileColors()
            || settingsComponent.isShowUntrackedFiles() != settings.isShowUntrackedFiles()
            || settingsComponent.isShowDeletedFiles() != settings.isShowDeletedFiles()
            || settingsComponent.isHideNoise() != settings.isHideNoise()
            || settingsComponent.isDoubleClickOpensDiff() != settings.isDoubleClickOpensDiff()
            || settingsComponent.isBuiltInNoiseRulesEnabled() != settings.isBuiltInNoiseRulesEnabled()
            || settingsComponent.isNoiseRulesModifiedFrom(settings.getNoiseRules());
    }

    @Override
    public void apply() throws ConfigurationException {
        settingsComponent.stopEditing(); // commit a pending cell edit before reading the table
        GitScopeSettings settings = GitScopeSettings.getInstance();
        List<NoiseRuleState> noiseRules = settingsComponent.getNoiseRules();
        String rulesProblem = NoiseRulesTablePanel.validate(noiseRules);
        if (rulesProblem != null) {
            throw new ConfigurationException(rulesProblem);
        }

        boolean tabColorsChanged = settingsComponent.isScopeFileColors() != settings.isScopeFileColors();
        boolean separateGutterChanged = settingsComponent.isSeparateGutterRendering() != settings.isSeparateGutterRendering();
        boolean noiseRulesChanged = settingsComponent.isBuiltInNoiseRulesEnabled() != settings.isBuiltInNoiseRulesEnabled()
            || !noiseRules.equals(settings.getNoiseRules());
        settings.setSeparateGutterRendering(settingsComponent.isSeparateGutterRendering());
        settings.setScopeFileColors(settingsComponent.isScopeFileColors());
        /* showUntrackedFiles/showDeletedFiles/hideNoise are only the new-project defaults (#111):
         * already-open projects have their own toggle state (see State.showUntrackedFiles/
         * showDeletedFiles/hideNoise, flipped via the Git Scope window buttons) and are
         * intentionally not refreshed here. */
        settings.setShowUntrackedFiles(settingsComponent.isShowUntrackedFiles());
        settings.setShowDeletedFiles(settingsComponent.isShowDeletedFiles());
        settings.setHideNoise(settingsComponent.isHideNoise());
        settings.setDoubleClickOpensDiff(settingsComponent.isDoubleClickOpensDiff());

        /* The noise setters bump noiseRulesVersion, which invalidates cached normalizers; only
         * touch them when something actually changed, or every Apply would wipe the caches. */
        if (noiseRulesChanged) {
            settings.setBuiltInNoiseRulesEnabled(settingsComponent.isBuiltInNoiseRulesEnabled());
            settings.setNoiseRules(noiseRules);
        }

        if (tabColorsChanged) {
            for (var project : ProjectManager.getInstance().getOpenProjects()) {
                if (!project.isDisposed()) {
                    ViewService viewService = project.getService(ViewService.class);
                    if (viewService != null && !viewService.isDisposed()) {
                        viewService.refreshFileColors();
                    }
                }
            }
        }

        if (separateGutterChanged) {
            for (var project : ProjectManager.getInstance().getOpenProjects()) {
                if (!project.isDisposed()) {
                    var gds = project.getService(service.GutterDataService.class);
                    if (gds != null) gds.republishAll();
                }
            }
        }

        if (noiseRulesChanged) {
            /* Hide Noise classifies files with the settings' rules, so already-collected scopes
               must be rebuilt to make the new rules take effect (same refresh the window's
               Hide Noise toggle does, see VcsTreeActions.refreshChanges). */
            for (var project : ProjectManager.getInstance().getOpenProjects()) {
                if (!project.isDisposed()) {
                    ViewService viewService = project.getService(ViewService.class);
                    if (viewService != null && !viewService.isDisposed()) {
                        viewService.collectChanges(true);
                    }
                    OpenDiffsRefresher.rediffAll(project);
                }
            }
        }
    }

    @Override
    public void reset() {
        GitScopeSettings settings = GitScopeSettings.getInstance();
        settingsComponent.stopEditing(); // before setNoiseRules replaces the table items
        settingsComponent.setSeparateGutterRendering(settings.isSeparateGutterRendering());
        settingsComponent.setScopeFileColors(settings.isScopeFileColors());
        settingsComponent.setShowUntrackedFiles(settings.isShowUntrackedFiles());
        settingsComponent.setShowDeletedFiles(settings.isShowDeletedFiles());
        settingsComponent.setHideNoise(settings.isHideNoise());
        settingsComponent.setDoubleClickOpensDiff(settings.isDoubleClickOpensDiff());
        settingsComponent.setBuiltInNoiseRulesEnabled(settings.isBuiltInNoiseRulesEnabled());
        settingsComponent.setNoiseRules(settings.getNoiseRules());
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}
