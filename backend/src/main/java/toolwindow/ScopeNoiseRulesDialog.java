package toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import model.MyModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import service.ViewService;
import settings.NoiseRuleState;
import settings.NoiseRulesTablePanel;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

/**
 * Modal editor for the noise rules that apply only while one Git Scope tab is selected, on top
 * of the global rules from Settings → Tools → Git Scope. On OK the rules are handed to
 * {@code ViewService.updateCurrentScopeNoiseRules}, which persists them and refreshes the
 * scope; Cancel leaves everything untouched.
 */
public class ScopeNoiseRulesDialog extends DialogWrapper {
    private static final String DIMENSION_SERVICE_KEY = "GitScope.ScopeNoiseRulesDialog";

    private final @Nullable ViewService viewService;
    private final NoiseRulesTablePanel rulesPanel;
    private final boolean opensWithNoRules;

    public ScopeNoiseRulesDialog(@NotNull Project project) {
        super(project, true);
        setModal(true);

        viewService = project.getService(ViewService.class);
        MyModel current = viewService == null ? null : viewService.getCurrent();
        String scopeName = current == null ? "unknown" : current.getDisplayName();
        setTitle("Noise Rules for Scope '" + scopeName + "'");

        rulesPanel = new NoiseRulesTablePanel();
        List<NoiseRuleState> rules = current == null ? List.of() : current.getNoiseRules();
        rulesPanel.setRules(rules);
        opensWithNoRules = rules.isEmpty();

        setResizable(true);
        init();
        rulesPanel.addModelChangeListener(this::refreshValidationBanner);
        refreshValidationBanner();
    }

    /** Lets the platform remember the dialog's size between invocations. */
    @Override
    protected String getDimensionServiceKey() {
        return DIMENSION_SERVICE_KEY;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JBLabel hintLabel = new JBLabel(
            "<html>Rules that apply only while this scope is selected, on top of the global rules from "
                + "Settings → Tools → Git Scope. Plain text works as a pattern (e.g. onvoice → invoice).</html>",
            UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);

        JPanel content = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        content.setBorder(JBUI.Borders.empty(10));
        content.add(hintLabel, BorderLayout.NORTH);
        content.add(rulesPanel, BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(JBUI.scale(620), JBUI.scale(320)));
        return content;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        rulesPanel.stopEditing(); // include a cell edit the user has not committed yet
        return validateRules();
    }

    private @Nullable ValidationInfo validateRules() {
        List<NoiseRuleState> rules = rulesPanel.getRules();
        String error = NoiseRulesTablePanel.validate(rules);
        if (error != null) {
            return new ValidationInfo(error);
        }
        String warning = NoiseRulesTablePanel.warn(rules);
        return warning == null ? null : new ValidationInfo(warning).asWarning().withOKEnabled();
    }

    private void refreshValidationBanner() {
        ValidationInfo info = validateRules();
        updateErrorInfo(info == null ? List.of() : List.of(info));
    }

    @Override
    protected void doOKAction() {
        List<NoiseRuleState> rules = rulesPanel.getRules();
        super.doOKAction();
        if (viewService != null) {
            viewService.updateCurrentScopeNoiseRules(rules);
        }
    }

    @Override
    public void show() {
        if (opensWithNoRules) {
            /* The common case is one rule, so start it off focused on Pattern: type the pattern,
               Tab to Replacement, type it, OK. Queued with any() because the dialog is modal and
               the request must run inside its event loop, once the window is displayable. */
            ApplicationManager.getApplication().invokeLater(
                () -> rulesPanel.addRuleAndFocusPattern(), ModalityState.any());
        }
        super.show();
    }
}