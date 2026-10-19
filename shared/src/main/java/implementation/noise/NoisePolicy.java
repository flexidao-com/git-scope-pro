package implementation.noise;

import com.intellij.diff.comparison.ComparisonPolicy;
import org.jetbrains.annotations.NotNull;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * Which whitespace policy is safe when deciding that two normalised texts are "the same".
 * <p>
 * Hiding noise by comparing normalised texts with {@code IGNORE_WHITESPACES} would also hide
 * indentation changes, which are meaningful in indentation-sensitive files: a Python re-indent can
 * move a statement into or out of a block. Those files therefore compare with
 * {@link ComparisonPolicy#DEFAULT}, so a whitespace-only difference still counts as a change.
 */
public final class NoisePolicy {

    /** File names (matched on the file name only) whose leading indentation is significant. */
    private static final List<PathMatcher> INDENTATION_SENSITIVE_MATCHERS = List.of(
            FileSystems.getDefault().getPathMatcher("glob:*.{py,pyi,yaml,yml,haml,pug,jade,slim,coffee,nim,fs,fsx}"),
            FileSystems.getDefault().getPathMatcher("glob:{Makefile,makefile,GNUmakefile}"),
            FileSystems.getDefault().getPathMatcher("glob:*.mk"));

    private NoisePolicy() {
    }

    /**
     * IGNORE_WHITESPACES, except for indentation-sensitive files (Python, YAML, Makefiles,
     * Haml/Pug/Slim, CoffeeScript, Nim, F#) where DEFAULT keeps leading indentation significant.
     */
    public static @NotNull ComparisonPolicy comparisonPolicyFor(@NotNull String filePath) {
        Path fileName = Path.of(fileNameOf(filePath));
        for (PathMatcher matcher : INDENTATION_SENSITIVE_MATCHERS) {
            if (matcher.matches(fileName)) {
                return ComparisonPolicy.DEFAULT;
            }
        }
        return ComparisonPolicy.IGNORE_WHITESPACES;
    }

    private static @NotNull String fileNameOf(@NotNull String filePath) {
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }
}