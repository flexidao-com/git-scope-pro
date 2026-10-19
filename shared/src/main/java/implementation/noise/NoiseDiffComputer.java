package implementation.noise;

import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.comparison.DiffTooBigException;
import com.intellij.diff.fragments.DiffFragment;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.fragments.LineFragmentImpl;
import com.intellij.diff.tools.util.text.LineOffsets;
import com.intellij.diff.tools.util.text.LineOffsetsUtil;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.util.IntPair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Diff computer attached to a diff request when Hide Noise is on: both texts are normalised with
 * every noise match blanked in place, compared, and fragments whose only difference is that
 * blanking are dropped, so an import-only change never shows up as a change. Kept fragments are
 * re-expressed against the original texts, and inner word fragments are only computed where no
 * replacement happened. Attach via {@link DiffUserDataKeysEx#CUSTOM_DIFF_COMPUTER}.
 */
public final class NoiseDiffComputer implements DiffUserDataKeysEx.DiffComputer {

    private final Supplier<NoiseNormalizer> normalizerSupplier;
    private final String filePath;

    public NoiseDiffComputer(@NotNull Supplier<NoiseNormalizer> normalizerSupplier, @NotNull String filePath) {
        this.normalizerSupplier = normalizerSupplier;
        this.filePath = filePath;
    }

    @Override
    public @NotNull List<LineFragment> compute(@NotNull CharSequence text1, @NotNull CharSequence text2,
                                               @NotNull ComparisonPolicy policy, boolean innerChanges,
                                               @NotNull ProgressIndicator indicator) {
        NoiseNormalizer normalizer = normalizerSupplier.get();
        String normalized1 = normalizer.normalizeKeepingLines(filePath, text1);
        String normalized2 = normalizer.normalizeKeepingLines(filePath, text2);
        LineOffsets originalOffsets1 = LineOffsetsUtil.create(text1);
        LineOffsets originalOffsets2 = LineOffsetsUtil.create(text2);
        boolean lineCountsPreserved = LineOffsetsUtil.create(normalized1).getLineCount() == originalOffsets1.getLineCount()
                && LineOffsetsUtil.create(normalized2).getLineCount() == originalOffsets2.getLineCount();
        if (!lineCountsPreserved) {
            /* A user rule added lines: line numbers no longer align, diff the original texts. */
            ComparisonManager manager = ComparisonManager.getInstance();
            return innerChanges
                    ? manager.compareLinesInner(text1, text2, policy, indicator)
                    : manager.compareLines(text1, text2, policy, indicator);
        }

        ComparisonPolicy noisePolicy = NoisePolicy.comparisonPolicyFor(filePath);
        List<LineFragment> normalizedFragments = ComparisonManager.getInstance()
                .compareLines(normalized1, normalized2, policy, indicator);
        List<LineFragment> result = new ArrayList<>(normalizedFragments.size());
        for (LineFragment fragment : normalizedFragments) {
            CharSequence normalizedPart1 = normalized1.subSequence(fragment.getStartOffset1(), fragment.getEndOffset1());
            CharSequence normalizedPart2 = normalized2.subSequence(fragment.getStartOffset2(), fragment.getEndOffset2());
            boolean noiseOnly = ComparisonManager.getInstance()
                    .isEquals(normalizedPart1, normalizedPart2, noisePolicy);
            if (noiseOnly) {
                continue;
            }
            result.add(remapToOriginal(fragment, normalizedPart1, normalizedPart2, text1, text2,
                    originalOffsets1, originalOffsets2, policy, innerChanges, indicator));
        }
        return result;
    }

    /** Rebuilds a kept fragment against the original texts, keeping the normalised line numbers. */
    private static @NotNull LineFragment remapToOriginal(@NotNull LineFragment fragment,
                                                         @NotNull CharSequence normalizedPart1,
                                                         @NotNull CharSequence normalizedPart2,
                                                         @NotNull CharSequence originalText1,
                                                         @NotNull CharSequence originalText2,
                                                         @NotNull LineOffsets originalOffsets1,
                                                         @NotNull LineOffsets originalOffsets2,
                                                         @NotNull ComparisonPolicy policy, boolean innerChanges,
                                                         @NotNull ProgressIndicator indicator) {
        IntPair offsets1 = rangeOffsets(originalOffsets1, fragment.getStartLine1(), fragment.getEndLine1());
        IntPair offsets2 = rangeOffsets(originalOffsets2, fragment.getStartLine2(), fragment.getEndLine2());
        CharSequence originalPart1 = originalText1.subSequence(offsets1.first, offsets1.second);
        CharSequence originalPart2 = originalText2.subSequence(offsets2.first, offsets2.second);
        boolean replacementInPart1 = !originalPart1.toString().equals(normalizedPart1.toString());
        boolean replacementInPart2 = !originalPart2.toString().equals(normalizedPart2.toString());
        /* With a replacement inside, word offsets from the normalised text would not match, so skip them. */
        List<DiffFragment> innerFragments = innerChanges && !replacementInPart1 && !replacementInPart2
                ? compareWordsOrNull(originalPart1, originalPart2, policy, indicator)
                : null;
        return new LineFragmentImpl(fragment.getStartLine1(), fragment.getEndLine1(),
                fragment.getStartLine2(), fragment.getEndLine2(),
                offsets1.first, offsets1.second, offsets2.first, offsets2.second, innerFragments);
    }

    /**
     * Start and end offsets of a line range in the original text, computed exactly like
     * {@code ComparisonManagerImpl.createLineFragment}: from the first line's start to the last
     * line's end (including its newline); an empty range sits at its line's start, or at the end
     * of the text when that line is just past the last one.
     */
    private static @NotNull IntPair rangeOffsets(@NotNull LineOffsets offsets, int startLine, int endLine) {
        boolean emptyRange = startLine == endLine;
        if (!emptyRange) {
            return new IntPair(offsets.getLineStart(startLine), offsets.getLineEnd(endLine - 1, true));
        }
        boolean rangeAtEndOfText = startLine == offsets.getLineCount();
        int offset = rangeAtEndOfText
                ? offsets.getLineEnd(offsets.getLineCount() - 1, true)
                : offsets.getLineStart(startLine);
        return new IntPair(offset, offset);
    }

    private static @Nullable List<DiffFragment> compareWordsOrNull(@NotNull CharSequence part1,
                                                                   @NotNull CharSequence part2,
                                                                   @NotNull ComparisonPolicy policy,
                                                                   @NotNull ProgressIndicator indicator) {
        try {
            return ComparisonManager.getInstance().compareWords(part1, part2, policy, indicator);
        } catch (DiffTooBigException e) {
            return null;
        }
    }
}