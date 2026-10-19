package settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.TableUtil;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ListTableModel;
import com.intellij.util.ui.UIUtil;
import implementation.noise.NoiseRule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reusable editor for the user-defined noise rules: the Enabled/Literal/Name/File glob/Pattern/Replacement
 * table plus a toolbar with Add, Remove, Up/Down, Export… and Import…. Used by both the global
 * settings page and the per-scope {@code toolwindow.ScopeNoiseRulesDialog}.
 */
public class NoiseRulesTablePanel extends JPanel {
    private static final int NAME_COLUMN_INDEX = 2;
    private static final int PATTERN_COLUMN_INDEX = 4;
    private static final int VISIBLE_RULE_ROWS = 6;
    private static final int TABLE_WIDTH = 600;
    private static final int ENABLED_COLUMN_WIDTH = 60;

    private final ListTableModel<NoiseRuleState> rulesModel;
    private final JBTable rulesTable;
    private final JBLabel warningLabel;
    private final boolean defaultLiteralForNewRows;

    /**
     * A rules editor without the grey hint label (the caller shows whatever hint fits its
     * context), where newly added rows default to {@code literal = true} for the scope dialog.
     */
    public NoiseRulesTablePanel() {
        this(false, true);
    }

    /**
     * @param showSettingsHint whether to show the grey hint describing the full rule syntax, as
     *                         the global settings page does
     */
    public NoiseRulesTablePanel(boolean showSettingsHint) {
        this(showSettingsHint, !showSettingsHint);
    }

    public NoiseRulesTablePanel(boolean showSettingsHint, boolean defaultLiteralForNewRows) {
        super(new BorderLayout());
        this.defaultLiteralForNewRows = defaultLiteralForNewRows;

        rulesModel = new ListTableModel<>(
            createEnabledColumn(),
            createLiteralColumn(),
            createTextColumn("Name", rule -> rule.name, (rule, value) -> rule.name = value),
            createTextColumn("File glob", rule -> rule.fileGlob, (rule, value) -> rule.fileGlob = value),
            createTextColumn("Pattern", rule -> rule.pattern, (rule, value) -> rule.pattern = value),
            createTextColumn("Replacement", rule -> rule.replacement, (rule, value) -> rule.replacement = value)
        );

        rulesTable = new JBTable(rulesModel);
        rulesTable.setPreferredScrollableViewportSize(new Dimension(
            JBUI.scale(TABLE_WIDTH), rulesTable.getRowHeight() * VISIBLE_RULE_ROWS));

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(rulesTable)
            .setAddAction(button -> addRuleAndStartEditingName())
            .addExtraAction(createExportAction())
            .addExtraAction(createImportAction());

        warningLabel = new JBLabel(AllIcons.General.Warning);
        warningLabel.setBorder(JBUI.Borders.emptyTop(4));
        warningLabel.setVisible(false);

        if (showSettingsHint) {
            JBLabel hintLabel = new JBLabel(
                "Rules are applied to both sides of a change before comparing. Text matching the pattern is replaced; "
                    + "lines that become identical are hidden by Hide Noise. Patterns are Java regular expressions "
                    + "(MULTILINE); replacement may use $1. File glob: empty = all files, e.g. *.ts or **/*.snap.",
                UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
            add(hintLabel, BorderLayout.NORTH);
            add(warningLabel, BorderLayout.SOUTH);
        }
        add(decorator.createPanel(), BorderLayout.CENTER);
        rulesModel.addTableModelListener(e -> updateWarningLabel());
    }

    /** Registers a listener invoked whenever the rules table model changes. */
    public void addModelChangeListener(@NotNull Runnable listener) {
        rulesModel.addTableModelListener(e -> listener.run());
    }

    /**
     * Commits any in-progress cell edit, so the model holds the text the user typed. The settings
     * dialog's apply/reset flows call this before {@link #getRules()}, which must not do it itself:
     * the dialog polls the rules (via isModified) while the user types and stopping the edit would
     * close the cell editor mid-keystroke.
     */
    public void stopEditing() {
        TableUtil.stopEditing(rulesTable);
    }

    /** A deep copy of the model's rules; an in-progress cell edit is not committed (see {@link #stopEditing()}). */
    public @NotNull List<NoiseRuleState> getRules() {
        List<NoiseRuleState> rules = new ArrayList<>(rulesModel.getItems().size());
        for (NoiseRuleState rule : rulesModel.getItems()) {
            rules.add(rule.copy());
        }
        return rules;
    }

    /** Replaces the table's rules with deep copies of {@code rules}; any in-progress edit is discarded. */
    public void setRules(@NotNull List<NoiseRuleState> rules) {
        stopEditing();
        List<NoiseRuleState> copies = new ArrayList<>(rules.size());
        for (NoiseRuleState rule : rules) {
            copies.add(rule.copy());
        }
        rulesModel.setItems(copies);
    }

    /** True when the table's rules (by value) differ from {@code rules}. */
    public boolean isModifiedFrom(@NotNull List<NoiseRuleState> rules) {
        return !getRules().equals(rules);
    }

    /**
     * Appends one empty, enabled rule and opens its Pattern cell for editing. Used when the
     * scope dialog opens without rules, so the common case is "type pattern, tab, type
     * replacement, OK".
     */
    public void addRuleAndFocusPattern() {
        rulesModel.addRow(createDefaultRule());
        int newRowIndex = rulesModel.getRowCount() - 1;
        rulesTable.getSelectionModel().setSelectionInterval(newRowIndex, newRowIndex);
        TableUtil.editCellAt(rulesTable, newRowIndex, PATTERN_COLUMN_INDEX);
    }

    /**
     * Validates {@code rules}, returning the first problem as a human-readable message or null
     * when every enabled rule is usable. Disabled rules and enabled rules with a blank pattern
     * are skipped (an unfinished row is not an error: the runtime skips blank patterns too).
     * Literal rules skip regex/replacement validation because their pattern and replacement are
     * quoted before compilation.
     */
    public static @Nullable String validate(@NotNull List<NoiseRuleState> rules) {
        for (NoiseRuleState rule : rules) {
            boolean ruleIsEnabled = rule.enabled;
            if (!ruleIsEnabled) {
                continue;
            }
            String pattern = rule.pattern == null ? "" : rule.pattern;
            boolean patternIsBlank = pattern.isBlank();
            if (patternIsBlank) {
                continue;
            }
            String ruleName = rule.name == null || rule.name.isBlank() ? "unnamed" : rule.name;
            boolean ruleIsRegex = !rule.literal;
            if (ruleIsRegex) {
                Pattern compiledPattern;
                try {
                    compiledPattern = Pattern.compile(pattern, Pattern.MULTILINE);
                } catch (PatternSyntaxException e) {
                    return "Noise rule '" + ruleName + "': invalid pattern: " + e.getDescription();
                }
                String replacement = rule.replacement == null ? "" : rule.replacement;
                String replacementProblem = NoiseRule.validateReplacement(compiledPattern, replacement);
                boolean replacementIsInvalid = replacementProblem != null;
                if (replacementIsInvalid) {
                    return "Noise rule '" + ruleName + "': " + replacementProblem;
                }
            }
            String fileGlob = rule.fileGlob == null ? "" : rule.fileGlob;
            boolean fileGlobIsPresent = !fileGlob.isEmpty();
            if (fileGlobIsPresent) {
                try {
                    /* FileSystems validates the glob eagerly and throws PatternSyntaxException. */
                    FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
                } catch (RuntimeException e) {
                    return "Noise rule '" + ruleName + "': invalid file glob: " + e.getMessage();
                }
            }
        }
        return null;
    }

    /**
     * Checks {@code rules} for shadowed rules when a regex rule is involved: literal rules are
     * automatically sorted longest-first at runtime, but regex rules keep their user-defined order,
     * so an enabled rule whose pattern contains an earlier enabled rule's pattern (with equal or
     * empty file globs) and where at least one of the two is a regex can never match. Returns the
     * first shadowing warning or null.
     */
    public static @Nullable String warn(@NotNull List<NoiseRuleState> rules) {
        for (int i = 0; i < rules.size(); i++) {
            NoiseRuleState earlier = rules.get(i);
            String earlierPattern = earlier.pattern == null ? "" : earlier.pattern;
            boolean earlierIsActive = earlier.enabled && !earlierPattern.isBlank();
            if (!earlierIsActive) {
                continue;
            }
            String earlierGlob = earlier.fileGlob == null ? "" : earlier.fileGlob;
            for (int j = i + 1; j < rules.size(); j++) {
                NoiseRuleState later = rules.get(j);
                String laterPattern = later.pattern == null ? "" : later.pattern;
                boolean laterIsActive = later.enabled && !laterPattern.isBlank();
                if (!laterIsActive) {
                    continue;
                }
                boolean regexRuleInvolved = !earlier.literal || !later.literal;
                String laterGlob = later.fileGlob == null ? "" : later.fileGlob;
                boolean globsAreEqualOrEmpty = earlierGlob.isEmpty() || earlierGlob.equals(laterGlob);
                boolean laterPatternContainsEarlier = laterPattern.contains(earlierPattern);
                boolean earlierShadowsLater = regexRuleInvolved
                    && globsAreEqualOrEmpty
                    && laterPatternContainsEarlier;
                if (earlierShadowsLater) {
                    return "Rule " + (j + 1) + " ('" + laterPattern + "') never matches: rule "
                        + (i + 1) + " ('" + earlierPattern + "') rewrites it first. Move it above.";
                }
            }
        }
        return null;
    }

    private void updateWarningLabel() {
        String warning = warn(rulesModel.getItems());
        warningLabel.setText(warning == null ? "" : warning);
        warningLabel.setVisible(warning != null);
    }

    private void addRuleAndStartEditingName() {
        rulesModel.addRow(createDefaultRule());
        int newRowIndex = rulesModel.getRowCount() - 1;
        rulesTable.getSelectionModel().setSelectionInterval(newRowIndex, newRowIndex);
        TableUtil.editCellAt(rulesTable, newRowIndex, NAME_COLUMN_INDEX);
    }

    private @NotNull NoiseRuleState createDefaultRule() {
        NoiseRuleState rule = new NoiseRuleState();
        rule.literal = defaultLiteralForNewRows;
        return rule;
    }

    private DumbAwareAction createExportAction() {
        return new DumbAwareAction("Export…", "Export the noise rules to a JSON file", null) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                exportNoiseRules();
            }
        };
    }

    private DumbAwareAction createImportAction() {
        return new DumbAwareAction("Import…", "Append noise rules from a JSON file", null) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                importNoiseRules();
            }
        };
    }

    private void exportNoiseRules() {
        stopEditing(); // include a cell edit the user has not committed yet
        List<NoiseRuleState> rules = getRules();
        FileSaverDialog saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(
            new FileSaverDescriptor("Export Noise Rules", "", "json"), (Project) null);
        VirtualFileWrapper target = saveDialog.save((VirtualFile) null, "noise-rules.json");
        if (target == null) {
            return;
        }
        try {
            /* VfsUtil.saveText closes a PersistentFSImpl stream whose close() asserts write access
               (ThreadingAssertions.assertWriteAccess), throwing a RuntimeException outside a write
               action and leaving the 0-byte file created by target.getVirtualFile(true) behind. */
            Path path = target.getFile().toPath();
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(rules);
            Files.writeString(path, json, StandardCharsets.UTF_8);
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        } catch (IOException e) {
            Messages.showErrorDialog(rulesTable, "Could not export the noise rules: " + e.getMessage(),
                "Export Noise Rules");
        }
    }

    private void importNoiseRules() {
        VirtualFile selectedFile = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor("json"), null, null);
        if (selectedFile == null) {
            return;
        }
        try {
            String json = readRuleFile(selectedFile);
            List<NoiseRuleState> importedRules = new Gson().fromJson(
                json, new TypeToken<List<NoiseRuleState>>() {}.getType());
            if (importedRules == null) {
                Messages.showErrorDialog(rulesTable, "The file does not contain a JSON array of noise rules.",
                    "Import Noise Rules");
                return;
            }
            TableUtil.stopEditing(rulesTable);
            for (NoiseRuleState rule : importedRules) {
                if (rule != null) {
                    rulesModel.addRow(rule.copy());
                }
            }
        } catch (IOException | JsonParseException e) {
            Messages.showErrorDialog(rulesTable, "Could not import the noise rules: " + e.getMessage(),
                "Import Noise Rules");
        }
    }

    private static @NotNull String readRuleFile(@NotNull VirtualFile selectedFile) throws IOException {
        try {
            return Files.readString(selectedFile.toNioPath(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return VfsUtilCore.loadText(selectedFile);
        }
    }

    private static ColumnInfo<NoiseRuleState, Boolean> createEnabledColumn() {
        return new ColumnInfo<>("Enabled") {
            @Override
            public Boolean valueOf(NoiseRuleState rule) {
                return rule.enabled;
            }

            @Override
            public Class<?> getColumnClass() {
                return Boolean.class;
            }

            @Override
            public boolean isCellEditable(NoiseRuleState rule) {
                return true;
            }

            @Override
            public void setValue(NoiseRuleState rule, Boolean value) {
                rule.enabled = Boolean.TRUE.equals(value);
            }

            @Override
            public int getWidth(JTable table) {
                return JBUI.scale(ENABLED_COLUMN_WIDTH);
            }
        };
    }

    private static ColumnInfo<NoiseRuleState, Boolean> createLiteralColumn() {
        return new ColumnInfo<>("Literal") {
            @Override
            public Boolean valueOf(NoiseRuleState rule) {
                return rule.literal;
            }

            @Override
            public Class<?> getColumnClass() {
                return Boolean.class;
            }

            @Override
            public boolean isCellEditable(NoiseRuleState rule) {
                return true;
            }

            @Override
            public void setValue(NoiseRuleState rule, Boolean value) {
                rule.literal = Boolean.TRUE.equals(value);
            }

            @Override
            public int getWidth(JTable table) {
                return JBUI.scale(ENABLED_COLUMN_WIDTH);
            }

            @Override
            public @Nullable String getTooltipText() {
                return "Match the pattern as plain text instead of a regular expression";
            }
        };
    }

    private static ColumnInfo<NoiseRuleState, String> createTextColumn(
        @NotNull String name,
        @NotNull Function<NoiseRuleState, String> getter,
        @NotNull BiConsumer<NoiseRuleState, String> setter
    ) {
        return new ColumnInfo<>(name) {
            @Override
            public String valueOf(NoiseRuleState rule) {
                return getter.apply(rule);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }

            @Override
            public boolean isCellEditable(NoiseRuleState rule) {
                return true;
            }

            @Override
            public void setValue(NoiseRuleState rule, String value) {
                setter.accept(rule, value);
            }
        };
    }
}