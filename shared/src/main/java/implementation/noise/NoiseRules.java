package implementation.noise;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import settings.GitScopeSettings;
import settings.NoiseRuleState;
import system.Defs;
import utils.Notification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Builds the effective {@link NoiseNormalizer} from the user's settings: the built-in rules first
 * (when enabled), then every enabled user rule in order. One tab's scope rules, when given, follow
 * the global ones with the same precedence. A user rule whose pattern or file glob does not
 * compile is skipped with a warning, so one bad rule cannot disable noise detection.
 */
public final class NoiseRules {

    private static final Logger LOG = Defs.getLogger(NoiseRules.class);

    /** Rule names already surfaced via a balloon warning in this IDE session. */
    private static final Set<String> WARNED_RULE_NAMES = ConcurrentHashMap.newKeySet();

    private NoiseRules() {
    }

    /** The normalizer described by the current {@link GitScopeSettings}. */
    public static @NotNull NoiseNormalizer fromSettings(@NotNull GitScopeSettings settings) {
        return fromSettings(settings, List.of());
    }

    /** The normalizer for the current settings plus one tab's scope rules. */
    public static @NotNull NoiseNormalizer fromSettings(@NotNull GitScopeSettings settings,
                                                        @NotNull List<NoiseRuleState> scopeRules) {
        return fromSettings(settings.isBuiltInNoiseRulesEnabled(), settings.getNoiseRules(), scopeRules);
    }

    /** Testable overload: builds a normalizer without touching the IDE service registry. */
    public static @NotNull NoiseNormalizer fromSettings(boolean builtInEnabled, @NotNull List<NoiseRuleState> rules) {
        return fromSettings(builtInEnabled, rules, List.of());
    }

    /**
     * Testable overload with scope rules: built-in (if enabled), then the global enabled rules,
     * then the scope ones — scope rules can, like global ones, only add to the effective set.
     */
    public static @NotNull NoiseNormalizer fromSettings(boolean builtInEnabled,
                                                        @NotNull List<NoiseRuleState> rules,
                                                        @NotNull List<NoiseRuleState> scopeRules) {
        List<NoiseRule> effectiveRules = new ArrayList<>();
        if (builtInEnabled) {
            effectiveRules.addAll(NoiseNormalizer.builtInRules());
        }
        addEnabled(rules, effectiveRules);
        addEnabled(scopeRules, effectiveRules);
        return new NoiseNormalizer(effectiveRules);
    }

    /**
     * Adds the enabled, compilable rules of {@code rules} to {@code effectiveRules}. Literal rules
     * are stable-sorted longest-pattern-first so a shorter substring rule cannot rewrite part of a
     * longer literal pattern before it runs; regex rules keep their user-defined order.
     */
    private static void addEnabled(@NotNull List<NoiseRuleState> rules, @NotNull List<NoiseRule> effectiveRules) {
        List<NoiseRuleState> sortedLiterals = new ArrayList<>();
        for (NoiseRuleState rule : rules) {
            if (rule.enabled && rule.literal) {
                sortedLiterals.add(rule);
            }
        }
        sortedLiterals.sort(
                Comparator.comparingInt((NoiseRuleState rule) -> nullToEmpty(rule.pattern).length()).reversed());

        int literalIndex = 0;
        for (NoiseRuleState rule : rules) {
            if (!rule.enabled) {
                continue;
            }
            NoiseRuleState orderedRule = rule.literal ? sortedLiterals.get(literalIndex++) : rule;
            NoiseRule converted = convert(orderedRule);
            if (converted != null) {
                effectiveRules.add(converted);
            }
        }
    }

    /**
     * Converts one enabled user rule; null (with a warning) when it is unusable. JSON imports can
     * leave fields null even though the class declares them, so string fields are coalesced to "".
     */
    private static @Nullable NoiseRule convert(@NotNull NoiseRuleState rule) {
        String name = nullToEmpty(rule.name);
        String rawPattern = nullToEmpty(rule.pattern);
        if (rawPattern.isBlank()) {
            /* An empty regex matches between every character and would insert the replacement everywhere. */
            warnSkipped(name, "empty pattern");
            return null;
        }
        String pattern = rule.literal ? Pattern.quote(rawPattern) : rawPattern;
        String rawReplacement = nullToEmpty(rule.replacement);
        String replacement = rule.literal ? Matcher.quoteReplacement(rawReplacement) : rawReplacement;
        String glob = nullToEmpty(rule.fileGlob);
        NoiseRule converted = new NoiseRule(name, glob.isEmpty() ? null : glob, pattern, replacement);
        try {
            /*
             * Compile up front: NoiseNormalizer's constructor must never be hit by a broken rule,
             * because it would throw and take every other rule down with it.
             */
            converted.compile();
        } catch (PatternSyntaxException e) {
            warnSkipped(name, e.getMessage());
            return null;
        }
        return converted;
    }

    private static void warnSkipped(@NotNull String name, @NotNull String reason) {
        LOG.warn("Skipping noise rule '" + name + "': " + reason);
        if (WARNED_RULE_NAMES.add(name)) {
            Notification.warn(null, Defs.APPLICATION_NAME, "Noise rule '" + name + "' skipped: " + reason);
        }
    }

    private static @NotNull String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}