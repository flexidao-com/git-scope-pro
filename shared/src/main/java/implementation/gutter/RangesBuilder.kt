// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Based on com.intellij.openapi.vcs.ex.RangesBuilder from the IntelliJ Platform
// (platform/diff-impl), modified for Git Scope Pro.
package implementation.gutter

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.tools.util.text.LineOffsetsUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.DumbProgressIndicator
import implementation.noise.NoiseNormalizer
import implementation.noise.NoisePolicy

/**
 * Creates ranges by comparing current document with VCS base revision.
 * Uses IntelliJ's diff utilities to compute line-level differences.
 */
object RangesBuilder {

    fun createRanges(current: Document, vcs: Document): List<Range> {
        return createRanges(current.immutableCharSequence, vcs.immutableCharSequence)
    }

    fun createRanges(current: CharSequence, vcs: CharSequence): List<Range> {
        return createRanges(current, vcs, null, null)
    }

    /**
     * Like [createRanges], but with [normalizer] and [filePath] non-null (Hide Noise on) both sides
     * are normalised keeping their lines, so an import-only difference becomes a whitespace-only
     * one. A fragment whose normalised sides are equal under [NoisePolicy.comparisonPolicyFor]
     * (whitespace-ignoring, except for indentation-sensitive files) is dropped, so it produces no
     * marker (mirrors `NoiseDiffComputer`). The line counts must be
     * preserved by the rules; otherwise the diff falls back to the raw texts, because the
     * normalised line numbers would no longer map to the document.
     */
    fun createRanges(current: CharSequence, vcs: CharSequence,
                     normalizer: NoiseNormalizer?, filePath: String?): List<Range> {
        return try {
            val fragments = compareLineFragments(vcs, current, normalizer, filePath)
            fragments.map { fragment ->
                Range(
                    line1 = fragment.startLine2,
                    line2 = fragment.endLine2,
                    vcsLine1 = fragment.startLine1,
                    vcsLine2 = fragment.endLine1
                )
            }
        } catch (e: DiffTooBigException) {
            emptyList()
        }
    }

    private fun compareLineFragments(vcs: CharSequence, current: CharSequence,
                                     normalizer: NoiseNormalizer?, filePath: String?): List<LineFragment> {
        val comparisonManager = ComparisonManager.getInstance()
        if (normalizer == null || filePath == null) {
            return comparisonManager.compareLines(vcs, current, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        }

        val normalizedVcs = normalizer.normalizeKeepingLines(filePath, vcs)
        val normalizedCurrent = normalizer.normalizeKeepingLines(filePath, current)
        val lineCountsPreserved =
            LineOffsetsUtil.create(normalizedVcs).lineCount == LineOffsetsUtil.create(vcs).lineCount &&
                    LineOffsetsUtil.create(normalizedCurrent).lineCount == LineOffsetsUtil.create(current).lineCount
        if (!lineCountsPreserved) {
            /* A user rule added or removed lines: normalised line numbers no longer match the
             * document, so diff the raw texts. */
            return comparisonManager.compareLines(vcs, current, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        }

        val noisePolicy = NoisePolicy.comparisonPolicyFor(filePath)
        val fragments = comparisonManager.compareLines(
            normalizedVcs, normalizedCurrent, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE
        )
        return fragments.filterNot { fragment ->
            comparisonManager.isEquals(
                normalizedVcs.subSequence(fragment.startOffset1, fragment.endOffset1),
                normalizedCurrent.subSequence(fragment.startOffset2, fragment.endOffset2),
                noisePolicy
            )
        }
    }
}
