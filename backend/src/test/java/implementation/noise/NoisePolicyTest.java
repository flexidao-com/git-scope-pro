package implementation.noise;

import com.intellij.diff.comparison.ComparisonPolicy;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class NoisePolicyTest {

    private static void assertKeepsIndentation(String filePath) {
        assertEquals(filePath + " should keep whitespace significant",
                ComparisonPolicy.DEFAULT, NoisePolicy.comparisonPolicyFor(filePath));
    }

    private static void assertIgnoresWhitespace(String filePath) {
        assertEquals(filePath + " should ignore whitespace",
                ComparisonPolicy.IGNORE_WHITESPACES, NoisePolicy.comparisonPolicyFor(filePath));
    }

    @Test
    public void indentationSensitiveExtensionsUseDefaultPolicy() {
        for (String filePath : List.of("a.py", "a.pyi", "a.yaml", "a.yml", "a.haml", "a.pug", "a.jade",
                "a.slim", "a.coffee", "a.nim", "a.fs", "a.fsx", "src/deep/a.py")) {
            assertKeepsIndentation(filePath);
        }
    }

    @Test
    public void makefileNamesUseDefaultPolicy() {
        for (String filePath : List.of("Makefile", "makefile", "GNUmakefile", "src/Makefile", "src/build.mk")) {
            assertKeepsIndentation(filePath);
        }
    }

    @Test
    public void everyOtherFileIgnoresWhitespace() {
        for (String filePath : List.of("A.java", "Main.kt", "app.ts", "README.md", "a.pyc",
                "Makefile.in", "src/maker.rb")) {
            assertIgnoresWhitespace(filePath);
        }
    }
}