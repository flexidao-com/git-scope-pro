package implementation.noise;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies {@link NoiseRule}s to file text before the two sides of a change are compared, so
 * that mechanical edits (import reordering, snapshot timestamps) do not count as differences.
 */
public final class NoiseNormalizer {

    private static final String JS_TS_GLOB = "*.{js,jsx,mjs,cjs,ts,tsx,mts,cts,vue,svelte}";

    /** Built-in rules: one per supported language, each swallowing whole import statements. */
    private static final List<NoiseRule> BUILT_IN_RULES = List.of(
            // Java: import a.b.C; | import static a.b.C.*; including the trailing newline
            new NoiseRule("java-imports", "*.java",
                    "^[ \\t]*import\\s+(?:static\\s+)?[\\w.]+(?:\\.\\*)?[ \\t]*;[ \\t]*\\R?",
                    ""),
            // Kotlin: import a.b.C | import a.b.* | import a.b.C as X; optional trailing semicolon
            new NoiseRule("kotlin-imports", "*.{kt,kts}",
                    "^[ \\t]*import\\s+[\\w.*]+(?:\\s+as\\s+\\w+)?[ \\t]*;?[ \\t]*\\R?",
                    ""),
            // JS/TS: import [type] x, { y } from '...' — the clause between import and from may
            // contain newlines, but never a quote or semicolon, so it cannot span statements
            new NoiseRule("js-ts-imports", JS_TS_GLOB,
                    "^[ \\t]*import\\b[ \\t]*(?:type[ \\t]+)?[^;'\"]*?\\bfrom\\b[ \\t]*['\"][^'\"]+['\"][ \\t]*;?[ \\t]*\\R?",
                    ""),
            // JS/TS: side-effect import, e.g. import './polyfill';
            new NoiseRule("js-ts-bare-imports", JS_TS_GLOB,
                    "^[ \\t]*import\\b[ \\t]*['\"][^'\"]+['\"][ \\t]*;?[ \\t]*\\R?",
                    ""),
            // JS/TS: TypeScript require import, e.g. import fs = require('fs');
            new NoiseRule("js-ts-require-imports", JS_TS_GLOB,
                    "^[ \\t]*import\\b[ \\t]*[\\w$]+[ \\t]*=[ \\t]*require[ \\t]*\\([ \\t]*['\"][^'\"]+['\"][ \\t]*\\)[ \\t]*;?[ \\t]*\\R?",
                    ""),
            // TS: import alias, e.g. import Foo = Ns.Foo; or export import Foo = Ns.Foo;
            new NoiseRule("ts-import-aliases", JS_TS_GLOB,
                    "^[ \\t]*(?:export[ \\t]+)?import[ \\t]+[\\w$]+[ \\t]*=[ \\t]*[\\w$.]+[ \\t]*;?[ \\t]*\\R?",
                    ""),
            // Python: from a import (\n b,\n c,\n) — parenthesised, may span lines
            new NoiseRule("python-parenthesised-from-imports", "*.py",
                    "(?s)^[ \\t]*from\\s+[\\w.]+\\s+import\\s+\\(.*?\\)[ \\t]*\\R?",
                    ""),
            // Python: from a import b, c as d — each name may carry an "as" alias
            new NoiseRule("python-from-imports", "*.py",
                    "^[ \\t]*from\\s+[\\w.]+\\s+import\\s+[\\w*]+(?:[ \\t]+as[ \\t]+\\w+)?(?:[ \\t]*,[ \\t]*[\\w*]+(?:[ \\t]+as[ \\t]+\\w+)?)*[ \\t]*\\R?",
                    ""),
            // Python: import a.b, c as d — each name may carry an "as" alias
            new NoiseRule("python-imports", "*.py",
                    "^[ \\t]*import\\s+[\\w.]+(?:[ \\t]+as[ \\t]+\\w+)?(?:[ \\t]*,[ \\t]*[\\w.]+(?:[ \\t]+as[ \\t]+\\w+)?)*[ \\t]*\\R?",
                    ""),
            // Go: import (\n "fmt"\n "os"\n) — block, may span lines
            new NoiseRule("go-import-blocks", "*.go",
                    "(?s)^[ \\t]*import\\s+\\(.*?\\)[ \\t]*\\R?",
                    ""),
            // Go: import "fmt"
            new NoiseRule("go-imports", "*.go",
                    "^[ \\t]*import\\s+[\"`][^\"`]+[\"`][ \\t]*\\R?",
                    "")
    );

    private final List<NoiseRule> rules;
    private final List<NoiseRule.Compiled> compiledRules;

    public NoiseNormalizer(@NotNull List<NoiseRule> rules) {
        this.rules = List.copyOf(rules);
        List<NoiseRule.Compiled> compiled = new ArrayList<>(rules.size());
        for (NoiseRule rule : rules) {
            compiled.add(rule.compile());
        }
        this.compiledRules = List.copyOf(compiled);
    }

    /** Built-in rules only (import statements for Java, Kotlin, JS/TS, Python, Go). */
    public static @NotNull NoiseNormalizer builtIn() {
        return new NoiseNormalizer(BUILT_IN_RULES);
    }

    /** The built-in rules, in application order, without building a normalizer around them. */
    public static @NotNull List<NoiseRule> builtInRules() {
        return BUILT_IN_RULES;
    }

    /** Applies every rule whose glob accepts {@code filePath}, in order, to {@code text}. */
    public @NotNull String normalize(@NotNull String filePath, @NotNull CharSequence text) {
        String result = text.toString();
        for (NoiseRule.Compiled rule : compiledRules) {
            if (rule.accepts(filePath)) {
                result = rule.applyTo(result);
            }
        }
        return result;
    }

    /**
     * Like {@link #normalize} but every replacement keeps the number of line breaks of the text
     * it replaced, so line N of the result is line N of the input with the noise blanked out.
     */
    public @NotNull String normalizeKeepingLines(@NotNull String filePath, @NotNull CharSequence text) {
        String result = text.toString();
        for (NoiseRule.Compiled rule : compiledRules) {
            if (rule.accepts(filePath)) {
                result = rule.applyKeepingLines(result);
            }
        }
        return result;
    }

    public @NotNull List<NoiseRule> rules() {
        return rules;
    }
}