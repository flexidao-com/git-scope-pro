package implementation.noise;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class NoiseNormalizerTest {

    private static final NoiseNormalizer BUILT_IN = NoiseNormalizer.builtIn();

    private static String normalize(String filePath, String text) {
        return BUILT_IN.normalize(filePath, text);
    }

    private static String keepingLines(String filePath, String text) {
        return BUILT_IN.normalizeKeepingLines(filePath, text);
    }

    /** Number of {@code \n} characters, which is what the line-preservation tests compare. */
    private static long lineCount(String text) {
        return text.chars().filter(c -> c == '\n').count();
    }

    @Test
    public void removesJavaImportLines() {
        assertEquals(
                "class A {}\n",
                normalize("A.java",
                        "import java.util.List;\nimport static java.util.Collections.emptyList;\nclass A {}\n"));
    }

    @Test
    public void removesKotlinImports() {
        assertEquals(
                "fun main() {}\n",
                normalize("Main.kt", "import kotlin.collections.List as KList\nimport kotlin.io.*\nfun main() {}\n"));
    }

    @Test
    public void removesKotlinScriptImports() {
        assertEquals("println(\"hi\")\n", normalize("build.kts", "import kotlin.io.*;\nprintln(\"hi\")\n"));
    }

    @Test
    public void removesJsTsSingleLineImports() {
        assertEquals("const x = 1;\n", normalize("app.js", "import { a, b } from './ab';\nconst x = 1;\n"));
        assertEquals("const x = 1;\n", normalize("app.tsx", "import React, { useState } from 'react';\nconst x = 1;\n"));
        assertEquals("console.log(1);\n", normalize("app.mjs", "import './polyfill';\nconsole.log(1);\n"));
        assertEquals("fs.readFileSync('x');\n", normalize("app.ts", "import fs = require('fs');\nfs.readFileSync('x');\n"));
    }

    @Test
    public void tsImportAliasRemoved() {
        assertEquals("use(Foo);\n", normalize("app.ts", "import Foo = Ns.Foo;\nuse(Foo);\n"));
        assertEquals("use(Foo);\n", normalize("types.ts", "export import Foo = Ns.Deep.Foo;\nuse(Foo);\n"));
        assertEquals("const x = importFoo = 1;\n", normalize("app.ts", "const x = importFoo = 1;\n"));
    }

    @Test
    public void removesTsMultiLineImport() {
        String text = "import {\n  a,\n  b,\n} from './x';\nconst y = 1;\n";
        assertEquals("const y = 1;\n", normalize("app.ts", text));
    }

    @Test
    public void leavesDynamicImportsAndImportLikeIdentifiersAlone() {
        String text = "const mod = await import('./dynamic');\nimport('./other');\nconst importantThing = 1;\n";
        assertEquals(text, normalize("app.ts", text));
    }

    @Test
    public void removesPythonSimpleImports() {
        assertEquals("x = 1\n", normalize("app.py", "import os, sys\nx = 1\n"));
        assertEquals("x = 1\n", normalize("app.py", "from a import b, c\nx = 1\n"));
    }

    @Test
    public void removesPythonParenthesisedImport() {
        String text = "from a import (\n    b,\n    c,\n)\nx = 1\n";
        assertEquals("x = 1\n", normalize("app.py", text));
    }

    @Test
    public void removesPythonImportsWithAsAliases() {
        assertEquals("x = 1\n", normalize("app.py", "from a import b as c, d as e\nx = 1\n"));
        assertEquals("x = 1\n", normalize("app.py", "import os.path as p, sys as s\nx = 1\n"));
    }

    @Test
    public void removesGoSingleLineImport() {
        assertEquals("func main() {}\n", normalize("main.go", "import \"fmt\"\nfunc main() {}\n"));
    }

    @Test
    public void removesGoImportBlock() {
        String text = "import (\n\t\"fmt\"\n\t\"os\"\n)\nfunc main() {}\n";
        assertEquals("func main() {}\n", normalize("main.go", text));
    }

    @Test
    public void keepingLinesBlanksTsImportsWithoutChangingTheLineCount() {
        String text = "import a from 'a';\nimport {\n  b,\n  c,\n} from 'b';\nconst x = 1;\n";
        String normalized = keepingLines("app.ts", text);
        assertEquals("\n\n\n\n\nconst x = 1;\n", normalized);
        assertEquals(6, lineCount(normalized));
        assertEquals(lineCount(text), lineCount(normalized));
        // The plain normalize still collapses the imports away entirely
        assertEquals("const x = 1;\n", normalize("app.ts", text));
    }

    @Test
    public void keepingLinesBlanksPythonParenthesisedImportWithoutChangingTheLineCount() {
        String text = "from a import (\n    b,\n    c,\n)\nx = 1\n";
        String normalized = keepingLines("app.py", text);
        assertEquals("\n\n\n\nx = 1\n", normalized);
        assertEquals(lineCount(text), lineCount(normalized));
        assertEquals("x = 1\n", normalize("app.py", text));
    }

    @Test
    public void keepingLinesBlanksGoImportBlockWithoutChangingTheLineCount() {
        String text = "import (\n\t\"fmt\"\n\t\"os\"\n)\nfunc main() {}\n";
        String normalized = keepingLines("main.go", text);
        assertEquals("\n\n\n\nfunc main() {}\n", normalized);
        assertEquals(lineCount(text), lineCount(normalized));
        assertEquals("func main() {}\n", normalize("main.go", text));
    }

    @Test
    public void keepingLinesPreservesLineCountForCrlfInput() {
        String text = "import a from 'a';\r\nimport {\r\n  b,\r\n} from 'b';\r\nconst x = 1;\r\n";
        String normalized = keepingLines("app.ts", text);
        // \R? matches a whole \r\n (counted as one line break) and the padding uses \n, so the
        // exact bytes differ but the number of \n stays the same
        assertEquals("\n\n\n\nconst x = 1;\r\n", normalized);
        assertEquals(5, lineCount(normalized));
        assertEquals(lineCount(text), lineCount(normalized));
    }

    @Test
    public void keepingLinesKeepsLinePositionsForASameLineUserRule() {
        NoiseRule rule = new NoiseRule("updated-at", null, "\"updatedAt\": \"[^\"]*\"", "\"updatedAt\": \"X\"");
        NoiseNormalizer normalizer = new NoiseNormalizer(List.of(rule));
        String text = "{\n  \"updatedAt\": \"2026-01-01\",\n  \"id\": 1\n}\n";
        String normalized = normalizer.normalizeKeepingLines("data.json", text);
        assertEquals("{\n  \"updatedAt\": \"X\",\n  \"id\": 1\n}\n", normalized);
        assertEquals(lineCount(text), lineCount(normalized));
    }

    @Test
    public void keepingLinesPadsAReplacementSpanningFewerLinesThanTheMatch() {
        NoiseRule rule = new NoiseRule("collapse-block", null, "BEGIN\n.*?\nEND", "COLLAPSED");
        NoiseNormalizer normalizer = new NoiseNormalizer(List.of(rule));
        String text = "BEGIN\nline\nEND\nrest\n";
        String normalized = normalizer.normalizeKeepingLines("f.txt", text);
        assertEquals("COLLAPSED\n\n\nrest\n", normalized);
        assertEquals(lineCount(text), lineCount(normalized));
    }

    @Test
    public void globRestrictsRulesToMatchingFiles() {
        String javaImport = "import java.util.List;\n";
        // The Java rule must not touch a TypeScript file
        assertEquals(javaImport, normalize("foo.ts", javaImport));
        // A glob without '/' matches the file name, so depth does not matter
        assertEquals("", normalize("src/deep/foo.ts", "import { a } from './a';\n"));
    }

    @Test
    public void ruleWithoutGlobAppliesToEveryFile() {
        NoiseRule rule = new NoiseRule("strip-todo", null, "TODO", "DONE");
        NoiseNormalizer normalizer = new NoiseNormalizer(List.of(rule));
        assertEquals("DONE", normalizer.normalize("deep/nested/file.unknown", "TODO"));
    }

    @Test
    public void globWithSlashMatchesTheWholePath() {
        NoiseRule rule = new NoiseRule("snapshots", "**/*.snap", "GENERATED", "X");
        NoiseNormalizer normalizer = new NoiseNormalizer(List.of(rule));
        assertEquals("X", normalizer.normalize("src/__snapshots__/a.snap", "GENERATED"));
        assertEquals("GENERATED", normalizer.normalize("src/__snapshots__/a.ts", "GENERATED"));
    }

    @Test
    public void userRuleReplacesMatches() {
        NoiseRule rule = new NoiseRule("updated-at", null, "\"updatedAt\": \"[^\"]*\"", "\"updatedAt\": \"X\"");
        NoiseNormalizer normalizer = new NoiseNormalizer(List.of(rule));
        assertEquals(
                "{\"updatedAt\": \"X\", \"id\": 1}",
                normalizer.normalize("data.json", "{\"updatedAt\": \"2026-01-01\", \"id\": 1}"));
    }

    @Test
    public void userRuleCanReferenceCaptureGroups() {
        NoiseRule rule = new NoiseRule("swap-assignment", null, "(\\w+)=(\\w+)", "$2=$1");
        NoiseNormalizer normalizer = new NoiseNormalizer(List.of(rule));
        assertEquals("b=a", normalizer.normalize("app.env", "a=b"));
    }

    @Test
    public void appliesRulesInTheConfiguredOrder() {
        NoiseRule aToB = new NoiseRule("a-to-b", null, "a", "b");
        NoiseRule bToC = new NoiseRule("b-to-c", null, "b", "c");
        assertEquals("c", new NoiseNormalizer(List.of(aToB, bToC)).normalize("f.txt", "a"));
        assertEquals("b", new NoiseNormalizer(List.of(bToC, aToB)).normalize("f.txt", "a"));
    }

    @Test
    public void textsDifferingOnlyInImportsNormaliseToEqualTexts() {
        String before = "import { b } from './b';\nimport { a } from './a';\n\nconst x = 1;\n";
        String after = "import { a } from './a';\nimport { c } from './c';\nimport { b } from './b';\n\nconst x = 1;\n";
        assertEquals(normalize("app.ts", before), normalize("app.ts", after));
    }

    @Test
    public void textsDifferingOutsideImportsStayDifferent() {
        String before = "import { a } from './a';\nconst x = 1;\n";
        String after = "import { a } from './a';\nconst x = 2;\n";
        assertNotEquals(normalize("app.ts", before), normalize("app.ts", after));
    }
}