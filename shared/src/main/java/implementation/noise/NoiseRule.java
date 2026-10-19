package implementation.noise;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One normalisation rule: text matching {@code pattern} is replaced by {@code replacement}
 * before two sides of a change are compared. {@code fileGlob} (nullable) restricts the rule
 * to file names matching a glob (e.g. "*.ts"): a glob without '/' matches the file name only,
 * one containing '/' (e.g. "**&#47;*.snap") against the whole path; null means every file.
 */
public record NoiseRule(@NotNull String name, @Nullable String fileGlob, @NotNull String pattern,
                        @NotNull String replacement) {

    /**
     * Checks that {@code replacement} can be expanded against {@code pattern} (same syntax as
     * {@link Matcher#appendReplacement(StringBuilder, String)}: {@code $N} and {@code ${name}}
     * group references, backslash-escaped literals), so a bad template can be rejected in settings
     * instead of failing at normalisation time. Returns a human-readable problem description, or
     * null when the template is valid.
     */
    public static @Nullable String validateReplacement(@NotNull Pattern pattern, @NotNull String replacement) {
        int groupCount = pattern.matcher("").groupCount();
        int index = 0;
        while (index < replacement.length()) {
            char c = replacement.charAt(index);
            if (c == '\\') {
                if (index + 1 == replacement.length()) {
                    return "replacement ends with a backslash; write \\\\ for a literal backslash";
                }
                index += 2;
                continue;
            }
            if (c != '$') {
                index++;
                continue;
            }
            if (index + 1 == replacement.length()) {
                return "replacement ends with '$'; write \\$ for a literal dollar sign";
            }
            char next = replacement.charAt(index + 1);
            if (next == '{') {
                int close = replacement.indexOf('}', index + 2);
                if (close < 0) {
                    return "replacement has an unclosed ${...} group name";
                }
                String name = replacement.substring(index + 2, close);
                if (name.isEmpty()) {
                    return "replacement has an empty ${} group name";
                }
                if (!pattern.namedGroups().containsKey(name)) {
                    return "replacement refers to group '" + name + "' but the pattern has no such named group";
                }
                index = close + 1;
                continue;
            }
            if (next < '0' || next > '9') {
                return "replacement has '$' not followed by a group number or {name}";
            }
            /*
             * Mirror Matcher's own parsing: consume digits while the accumulated index stays
             * within the group count, so "$12" next to one group means group 1 then a literal "2",
             * while "$2" next to one group is an error.
             */
            int groupIndex = next - '0';
            index += 2;
            while (index < replacement.length() && Character.isDigit(replacement.charAt(index))) {
                int candidate = groupIndex * 10 + (replacement.charAt(index) - '0');
                if (candidate > groupCount) {
                    break;
                }
                groupIndex = candidate;
                index++;
            }
            if (groupIndex > groupCount) {
                return "replacement refers to group " + groupIndex + " but the pattern has " + groupCount;
            }
        }
        return null;
    }

    /**
     * Compiles this rule into {@link Compiled}: the regex {@link Pattern} and the glob
     * {@link PathMatcher} are built here, once, instead of on every normalisation call.
     */
    @NotNull Compiled compile() {
        Pattern compiledPattern = Pattern.compile(pattern, Pattern.MULTILINE);
        PathMatcher compiledGlob = fileGlob == null
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
        return new Compiled(this, compiledPattern, compiledGlob);
    }

    /** A rule with its regex and glob compiled; build one via {@link #compile()}. */
    static final class Compiled {

        private final NoiseRule rule;
        private final Pattern pattern;
        private final PathMatcher glob; // null when the rule applies to every file

        private Compiled(@NotNull NoiseRule rule, @NotNull Pattern pattern, @Nullable PathMatcher glob) {
            this.rule = rule;
            this.pattern = pattern;
            this.glob = glob;
        }

        /**
         * True when the rule's glob accepts {@code filePath}. A glob without '/' matches the
         * file name only (so "*.ts" works for any directory); a glob with '/' matches the whole
         * path string that was handed to the normalizer.
         */
        boolean accepts(@NotNull String filePath) {
            if (glob == null) {
                return true;
            }
            String candidate = rule.fileGlob().contains("/") ? filePath : fileNameOf(filePath);
            return glob.matches(Path.of(candidate));
        }

        /** Replaces every match of the rule's regex in {@code text}. */
        @NotNull String applyTo(@NotNull CharSequence text) {
            try {
                return pattern.matcher(text).replaceAll(rule.replacement());
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                /* A bad replacement must never break collection: leave the text unchanged. */
                return text.toString();
            }
        }

        /**
         * Replaces every match of the rule's regex in {@code text} while preserving the line
         * structure: each replacement is padded with {@code \n} up to the number of line breaks
         * of the text it replaced, so line N of the result is line N of the input. Line breaks
         * are counted as the regex {@code \R} family would match them: every {@code \n}
         * (including the one in {@code \r\n}) plus every lone {@code \r}.
         * A replacement containing MORE line breaks than its match is kept as is: user rules
         * should never add lines, and there is no logger here to report the violation.
         */
        @NotNull String applyKeepingLines(@NotNull String text) {
            try {
                Matcher matcher = pattern.matcher(text);
                StringBuilder result = new StringBuilder();
                int appendPosition = 0; // mirrors the matcher's own append position, which we advance manually
                while (matcher.find()) {
                    /*
                     * appendReplacement appends the text before the match first and the expanded
                     * replacement (so $1 works) second, so expand into a scratch builder and keep
                     * only the replacement part.
                     */
                    StringBuilder scratch = new StringBuilder();
                    matcher.appendReplacement(scratch, rule.replacement());
                    String replacement = scratch.substring(matcher.start() - appendPosition);

                    result.append(text, appendPosition, matcher.start());
                    result.append(replacement);
                    int replacementLineBreaks = lineBreakCount(replacement);
                    int matchedLineBreaks = lineBreakCount(matcher.group());
                    for (int i = replacementLineBreaks; i < matchedLineBreaks; i++) {
                        result.append('\n');
                    }
                    appendPosition = matcher.end();
                }
                matcher.appendTail(result);
                return result.toString();
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                /* A bad replacement must never break collection: leave the text unchanged. */
                return text;
            }
        }

        private static int lineBreakCount(@NotNull CharSequence text) {
            int count = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                boolean loneCarriageReturn = c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n');
                if (c == '\n' || loneCarriageReturn) {
                    count++;
                }
            }
            return count;
        }

        private static @NotNull String fileNameOf(@NotNull String filePath) {
            int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            return slash >= 0 ? filePath.substring(slash + 1) : filePath;
        }
    }
}