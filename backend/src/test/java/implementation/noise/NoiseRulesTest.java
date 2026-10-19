package implementation.noise;

import org.junit.Test;
import settings.GitScopeSettings;
import settings.NoiseRuleState;
import settings.NoiseRulesTablePanel;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NoiseRulesTest {

    private static List<String> ruleNames(NoiseNormalizer normalizer) {
        return normalizer.rules().stream().map(NoiseRule::name).toList();
    }

    @Test
    public void builtInDisabledKeepsOnlyTheUserRules() {
        NoiseRuleState rule = new NoiseRuleState(true, "strip-dates", "*.json",
                "\"date\": \"[^\"]*\"", "\"date\": \"X\"");
        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(rule));

        assertEquals(1, normalizer.rules().size());
        NoiseRule converted = normalizer.rules().get(0);
        assertEquals("strip-dates", converted.name());
        assertEquals("*.json", converted.fileGlob());
        assertEquals("\"date\": \"[^\"]*\"", converted.pattern());
        assertEquals("\"date\": \"X\"", converted.replacement());
    }

    @Test
    public void emptyGlobBecomesNullGlob() {
        NoiseRuleState rule = new NoiseRuleState(true, "todo", "", "TODO", "DONE");
        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(rule));

        assertNull(normalizer.rules().get(0).fileGlob());
        assertEquals("DONE", normalizer.normalize("deep/nested/f.unknown", "TODO"));
    }

    @Test
    public void disabledRulesAreSkipped() {
        NoiseRuleState disabled = new NoiseRuleState(false, "disabled", "", "a", "b");
        NoiseRuleState enabled = new NoiseRuleState(true, "enabled", "", "b", "c");

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(disabled, enabled));

        assertEquals(List.of("enabled"), ruleNames(normalizer));
    }

    @Test
    public void invalidRegexIsSkippedAndValidRulesAreKept() {
        NoiseRuleState invalid = new NoiseRuleState(true, "broken", "", "([unclosed", "x");
        NoiseRuleState valid = new NoiseRuleState(true, "fine", "", "a", "b");

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(invalid, valid));

        assertEquals(List.of("fine"), ruleNames(normalizer));
        assertEquals("b", normalizer.normalize("f.txt", "a"));
    }

    @Test
    public void builtInRulesComeFirstThenUserRulesInOrder() {
        NoiseRuleState first = new NoiseRuleState(true, "first", "", "x", "y");
        NoiseRuleState second = new NoiseRuleState(true, "second", "", "y", "z");

        NoiseNormalizer normalizer = NoiseRules.fromSettings(true, List.of(first, second));

        List<NoiseRule> builtInRules = NoiseNormalizer.builtInRules();
        List<String> names = ruleNames(normalizer);
        assertEquals(builtInRules.stream().map(NoiseRule::name).toList(),
                names.subList(0, builtInRules.size()));
        assertEquals(List.of("first", "second"), names.subList(builtInRules.size(), names.size()));
    }

    @Test
    public void builtInThenGlobalThenScopeRules() {
        NoiseRuleState global = new NoiseRuleState(true, "global", "", "x", "y");
        NoiseRuleState scope = new NoiseRuleState(true, "scope", "", "y", "z");

        NoiseNormalizer normalizer = NoiseRules.fromSettings(true, List.of(global), List.of(scope));

        List<NoiseRule> builtInRules = NoiseNormalizer.builtInRules();
        List<String> names = ruleNames(normalizer);
        assertEquals(builtInRules.stream().map(NoiseRule::name).toList(),
                names.subList(0, builtInRules.size()));
        assertEquals(List.of("global", "scope"), names.subList(builtInRules.size(), names.size()));
    }

    @Test
    public void scopeRulesApplyTheirOwnGlobAndDisabledOrBrokenOnesAreSkipped() {
        NoiseRuleState disabled = new NoiseRuleState(false, "disabled", "", "a", "b");
        NoiseRuleState broken = new NoiseRuleState(true, "broken", "", "([unclosed", "x");
        NoiseRuleState scoped = new NoiseRuleState(true, "rename", "*.java", "onvoice", "invoice");

        NoiseNormalizer normalizer =
                NoiseRules.fromSettings(false, List.of(), List.of(disabled, broken, scoped));

        assertEquals(List.of("rename"), ruleNames(normalizer));
        assertEquals("invoice", normalizer.normalize("src/Main.java", "onvoice"));
        assertEquals("onvoice", normalizer.normalize("src/main.kt", "onvoice"));
    }

    @Test
    public void settingsOverloadAppendsScopeRules() {
        /* The overload the classifier and the frontend cache actually use. */
        GitScopeSettings settings = new GitScopeSettings();
        settings.builtInNoiseRulesEnabled = false;
        settings.setNoiseRules(List.of(new NoiseRuleState(true, "global", "", "a", "b")));
        NoiseRuleState scope = new NoiseRuleState(true, "scope", "", "b", "c");

        NoiseNormalizer normalizer = NoiseRules.fromSettings(settings, List.of(scope));

        assertEquals(List.of("global", "scope"), ruleNames(normalizer));
        assertEquals("c", normalizer.normalize("f.txt", "a"));
    }

    @Test
    public void settingsOverloadWithoutScopeRulesMatchesTheSingleArgumentOverload() {
        GitScopeSettings settings = new GitScopeSettings();
        settings.builtInNoiseRulesEnabled = false;
        settings.setNoiseRules(List.of(new NoiseRuleState(true, "only", "", "a", "b")));

        assertEquals(ruleNames(NoiseRules.fromSettings(settings, List.of())),
                ruleNames(NoiseRules.fromSettings(settings)));
    }

    @Test
    public void blankPatternRulesAreSkipped() {
        /* An empty regex matches between every character and would splice the replacement into every line. */
        NoiseRuleState blank = new NoiseRuleState(true, "blank", "", "", "X");
        NoiseRuleState valid = new NoiseRuleState(true, "fine", "", "a", "b");

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(blank, valid));

        assertEquals(List.of("fine"), ruleNames(normalizer));
    }

    @Test
    public void nullStringFieldsFromImportedJsonAreCoalesced() {
        NoiseRuleState imported = new NoiseRuleState();
        imported.name = null;
        imported.fileGlob = null;
        imported.pattern = "a";
        imported.replacement = null;

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(imported));

        NoiseRule converted = normalizer.rules().get(0);
        assertEquals("", converted.name());
        assertNull(converted.fileGlob());
        assertEquals("", converted.replacement());
        assertEquals("", normalizer.normalize("f.txt", "a"));
    }

    @Test
    public void nullPatternFromImportedJsonSkipsTheRule() {
        NoiseRuleState imported = new NoiseRuleState();
        imported.pattern = null;

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(imported));

        assertEquals(List.of(), ruleNames(normalizer));
    }

    @Test
    public void literalRuleMatchesMetacharactersAsPlainText() {
        NoiseRuleState literalRule = new NoiseRuleState(true, "dotted-rename", "",
                "invoice.id", "order.id", true);

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(literalRule));

        assertEquals("order.id", normalizer.normalize("f.java", "invoice.id"));
        assertEquals("invoiceXid", normalizer.normalize("f.java", "invoiceXid"));
    }

    @Test
    public void literalRuleKeepsDollarAndBackslashInReplacementLiteral() {
        NoiseRuleState literalRule = new NoiseRuleState(true, "dollar-rename", "",
                "getOnvoice([0])", "$invoice\\id$", true);

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(literalRule));

        assertEquals("$invoice\\id$", normalizer.normalize("f.java", "getOnvoice([0])"));
        assertEquals("$invoice\\id$\n", normalizer.normalizeKeepingLines("f.java", "getOnvoice([0])\n"));
    }

    @Test
    public void literalRulesApplyLongestPatternFirstRegardlessOfOrder() {
        NoiseRuleState shorter = new NoiseRuleState(true, "short", "", "uuid()", "X", true);
        NoiseRuleState longer = new NoiseRuleState(true, "long", "", "brandUuidUnsafe(uuid())", "Y", true);

        NoiseNormalizer fromGlobal = NoiseRules.fromSettings(false, List.of(shorter, longer));
        assertEquals("Y", fromGlobal.normalize("f.ts", "brandUuidUnsafe(uuid())"));
        assertEquals("Y", fromGlobal.normalizeKeepingLines("f.ts", "brandUuidUnsafe(uuid())"));

        NoiseNormalizer fromScope = NoiseRules.fromSettings(false, List.of(), List.of(shorter, longer));
        assertEquals("Y", fromScope.normalize("f.ts", "brandUuidUnsafe(uuid())"));
    }

    @Test
    public void literalRulesKeepRelativeOrderOnEqualLengthAndRegexRulesKeepUserOrder() {
        NoiseRuleState lit1 = new NoiseRuleState(true, "lit1", "", "aa", "bb", true);
        NoiseRuleState lit2 = new NoiseRuleState(true, "lit2", "", "bb", "cc", true);
        NoiseRuleState regex1 = new NoiseRuleState(true, "regex1", "", "uuid\\(\\)", "X", false);
        NoiseRuleState regex2 = new NoiseRuleState(true, "regex2", "", "brand\\(uuid\\(\\)\\)", "Y", false);

        NoiseNormalizer normalizer = NoiseRules.fromSettings(false, List.of(lit1, lit2, regex1, regex2));

        assertEquals(List.of("lit1", "lit2", "regex1", "regex2"), ruleNames(normalizer));
        assertEquals("cc", normalizer.normalize("f.ts", "aa"));
        assertEquals("brand(X)", normalizer.normalize("f.ts", "brand(uuid())"));
    }

    @Test
    public void warnReportsRuleShadowedWhenARegexRuleIsInvolved() {
        NoiseRuleState r1 = new NoiseRuleState(true, "", "", "foo1", "bar1", true);
        NoiseRuleState r2 = new NoiseRuleState(true, "", "", "foo2", "bar2", true);
        NoiseRuleState r3 = new NoiseRuleState(true, "", "", "foo3", "bar3", true);
        NoiseRuleState r4 = new NoiseRuleState(true, "", "", "uuid()", "uuidV7()", false);
        NoiseRuleState r5 = new NoiseRuleState(true, "", "", "brandUuidUnsafe(uuid())", "uuidV7()", true);

        assertEquals(
                "Rule 5 ('brandUuidUnsafe(uuid())') never matches: rule 4 ('uuid()') rewrites it first. Move it above.",
                NoiseRulesTablePanel.warn(List.of(r1, r2, r3, r4, r5)));
    }

    @Test
    public void warnDoesNotReportPureLiteralRulesBecauseLongestMatchesFirst() {
        NoiseRuleState shorter = new NoiseRuleState(true, "", "", "uuid()", "uuidV7()", true);
        NoiseRuleState longer = new NoiseRuleState(true, "", "", "brandUuidUnsafe(uuid())", "uuidV7()", true);

        assertNull(NoiseRulesTablePanel.warn(List.of(shorter, longer)));
        assertNull(NoiseRulesTablePanel.warn(List.of(longer, shorter)));
    }

    @Test
    public void warnSkipsDisabledBlankOrDifferentGlobRules() {
        NoiseRuleState disabledEarlier = new NoiseRuleState(false, "", "", "uuid()", "uuidV7()", false);
        NoiseRuleState blankEarlier = new NoiseRuleState(true, "", "", "", "uuidV7()", false);
        NoiseRuleState otherGlobEarlier = new NoiseRuleState(true, "", "*.ts", "uuid()", "uuidV7()", false);
        NoiseRuleState target = new NoiseRuleState(true, "", "*.java", "brandUuidUnsafe(uuid())", "uuidV7()", false);

        assertNull(NoiseRulesTablePanel.warn(List.of(
                disabledEarlier, blankEarlier, otherGlobEarlier, target)));

        NoiseRuleState sameGlobEarlier = new NoiseRuleState(true, "", "*.java", "uuid()", "uuidV7()", false);
        assertEquals(
                "Rule 2 ('brandUuidUnsafe(uuid())') never matches: rule 1 ('uuid()') rewrites it first. Move it above.",
                NoiseRulesTablePanel.warn(List.of(sameGlobEarlier, target)));
    }
}