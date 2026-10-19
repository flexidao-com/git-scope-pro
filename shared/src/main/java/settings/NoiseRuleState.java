package settings;

import java.util.Objects;

/**
 * One user-defined noise rule, as persisted in {@code gitScopeSettings.xml}.
 * <p>
 * XmlSerializer-friendly: public fields and a public no-argument constructor. Field values carry
 * the rule's serialised form only; the backend converts them to its runtime rule type. An empty
 * {@code fileGlob} means every file; {@code pattern} is a Java regular expression compiled with
 * {@link java.util.regex.Pattern#MULTILINE} (or plain text when {@code literal} is true);
 * {@code replacement} may reference capture groups ({@code $1}) unless {@code literal} is true.
 */
public final class NoiseRuleState {

    public boolean enabled = true;

    /** When true, {@code pattern} and {@code replacement} are matched and substituted as plain text. */
    public boolean literal = false;

    public String name = "";

    /** Glob restricting the rule to matching files; "" means every file. */
    public String fileGlob = "";

    /** Java regex (or literal text when {@link #literal} is true) whose matches are replaced before comparing. */
    public String pattern = "";

    /** Replacement text for each match; may reference capture groups like {@code $1} unless {@link #literal} is true. */
    public String replacement = "";

    public NoiseRuleState() {
    }

    public NoiseRuleState(boolean enabled, String name, String fileGlob, String pattern, String replacement) {
        this(enabled, name, fileGlob, pattern, replacement, false);
    }

    public NoiseRuleState(boolean enabled, String name, String fileGlob, String pattern, String replacement,
                          boolean literal) {
        this.enabled = enabled;
        this.literal = literal;
        this.name = name;
        this.fileGlob = fileGlob;
        this.pattern = pattern;
        this.replacement = replacement;
    }

    /** A deep copy, used to keep settings' own list independent from its callers. */
    public NoiseRuleState copy() {
        return new NoiseRuleState(enabled, name, fileGlob, pattern, replacement, literal);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NoiseRuleState)) {
            return false;
        }
        NoiseRuleState rule = (NoiseRuleState) other;
        return enabled == rule.enabled
                && literal == rule.literal
                && Objects.equals(name, rule.name)
                && Objects.equals(fileGlob, rule.fileGlob)
                && Objects.equals(pattern, rule.pattern)
                && Objects.equals(replacement, rule.replacement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, literal, name, fileGlob, pattern, replacement);
    }
}