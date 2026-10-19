package settings;

import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

/**
 * UI component for Git Scope settings panel.
 * Provides the global behaviour checkboxes and the table of user-defined noise rules.
 */
public class GitScopeSettingsComponent {
    private final JPanel mainPanel;
    private final JBCheckBox separateGutterRenderingCheckBox;
    private final JBCheckBox scopeFileColorsCheckBox;
    private final JBCheckBox showUntrackedFilesCheckBox;
    private final JBCheckBox showDeletedFilesCheckBox;
    private final JBCheckBox hideNoiseCheckBox;
    private final JBCheckBox doubleClickOpensDiffCheckBox;
    private final JBCheckBox builtInNoiseRulesCheckBox;
    private final NoiseRulesTablePanel noiseRulesPanel;

    public GitScopeSettingsComponent() {
        separateGutterRenderingCheckBox = new JBCheckBox(
            "Separate Git Scope and IDE gutter rendering"
        );

        scopeFileColorsCheckBox = new JBCheckBox(
            "Color files based on Git Scope"
        );

        showUntrackedFilesCheckBox = new JBCheckBox(
            "Display untracked files"
        );

        showDeletedFilesCheckBox = new JBCheckBox(
            "Display deleted files"
        );

        hideNoiseCheckBox = new JBCheckBox(
            "Hide noise (import-only and whitespace-only changes) by default"
        );

        doubleClickOpensDiffCheckBox = new JBCheckBox(
            "Double-click on a file opens its diff instead of the file"
        );

        builtInNoiseRulesCheckBox = new JBCheckBox(
            "Use built-in import rules (Java, Kotlin, JS/TS, Python, Go)"
        );

        noiseRulesPanel = new NoiseRulesTablePanel(true);

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new TitledSeparator("Gutter Rendering"))
            .addComponent(separateGutterRenderingCheckBox, 1)
            .addTooltip("When enabled, Git Scope gutter markers is rendered to the left of the line numbers. ")
            .addVerticalGap(10)
            .addComponent(new TitledSeparator("File Colors"))
            .addComponent(scopeFileColorsCheckBox, 1)
            .addTooltip("When enabled (default), project and editor file colors reflect the active Git Scope")
            .addVerticalGap(10)
            .addComponent(new TitledSeparator("Working Tree (default for new projects)"))
            .addComponent(showUntrackedFilesCheckBox, 1)
            .addTooltip("Initial state of the \"Show Untracked Files\" toggle in the Git Scope window")
            .addComponent(showDeletedFilesCheckBox, 1)
            .addTooltip("Initial state of the \"Show Deleted Files\" toggle in the Git Scope window")
            .addComponent(hideNoiseCheckBox, 1)
            .addTooltip("Initial state of the \"Hide Noise\" toggle in the Git Scope window")
            .addVerticalGap(10)
            .addComponent(new TitledSeparator("Noise rules"))
            .addComponent(builtInNoiseRulesCheckBox, 1)
            .addTooltip("When enabled (default), the built-in import rules normalise Java, Kotlin, JS/TS, Python and Go imports before comparing")
            .addComponent(noiseRulesPanel, 0)
            .addVerticalGap(10)
            .addComponent(new TitledSeparator("Change tree"))
            .addComponent(doubleClickOpensDiffCheckBox, 1)
            .addTooltip("When enabled, double-clicking a file opens its scope diff instead of the file. Single-click preview is unaffected")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

        mainPanel.setBorder(JBUI.Borders.empty(10));
    }

    public JPanel getPanel() {
        return mainPanel;
    }

    public JComponent getPreferredFocusedComponent() {
        return separateGutterRenderingCheckBox;
    }

    public boolean isSeparateGutterRendering() {
        return separateGutterRenderingCheckBox.isSelected();
    }

    public void setSeparateGutterRendering(boolean value) {
        separateGutterRenderingCheckBox.setSelected(value);
    }

    public boolean isScopeFileColors() {
        return scopeFileColorsCheckBox.isSelected();
    }

    public void setScopeFileColors(boolean value) {
        scopeFileColorsCheckBox.setSelected(value);
    }

    public boolean isShowUntrackedFiles() {
        return showUntrackedFilesCheckBox.isSelected();
    }

    public void setShowUntrackedFiles(boolean value) {
        showUntrackedFilesCheckBox.setSelected(value);
    }

    public boolean isShowDeletedFiles() {
        return showDeletedFilesCheckBox.isSelected();
    }

    public void setShowDeletedFiles(boolean value) {
        showDeletedFilesCheckBox.setSelected(value);
    }

    public boolean isHideNoise() {
        return hideNoiseCheckBox.isSelected();
    }

    public void setHideNoise(boolean value) {
        hideNoiseCheckBox.setSelected(value);
    }

    public boolean isDoubleClickOpensDiff() {
        return doubleClickOpensDiffCheckBox.isSelected();
    }

    public void setDoubleClickOpensDiff(boolean value) {
        doubleClickOpensDiffCheckBox.setSelected(value);
    }

    public boolean isBuiltInNoiseRulesEnabled() {
        return builtInNoiseRulesCheckBox.isSelected();
    }

    public void setBuiltInNoiseRulesEnabled(boolean value) {
        builtInNoiseRulesCheckBox.setSelected(value);
    }

    public void stopEditing() {
        noiseRulesPanel.stopEditing();
    }

    /** A deep copy of the table's rules; an in-progress cell edit is not committed (see {@link #stopEditing()}). */
    public @NotNull List<NoiseRuleState> getNoiseRules() {
        return noiseRulesPanel.getRules();
    }

    /** Replaces the table's rules with deep copies of {@code rules}; any in-progress edit is discarded. */
    public void setNoiseRules(@NotNull List<NoiseRuleState> rules) {
        noiseRulesPanel.setRules(rules);
    }

    /** True when the table's rules differ from {@code rules}; used by the settings dialog's isModified poll. */
    public boolean isNoiseRulesModifiedFrom(@NotNull List<NoiseRuleState> rules) {
        return noiseRulesPanel.isModifiedFrom(rules);
    }
}