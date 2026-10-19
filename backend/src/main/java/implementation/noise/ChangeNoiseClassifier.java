package implementation.noise;

import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import settings.GitScopeSettings;
import settings.NoiseRuleState;
import system.Defs;
import utils.ScopeDiff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a change is noise: a text-file modification whose base and working-tree sides are
 * equal once the noise rules are applied under the file's comparison policy. The rules are the
 * built-in ones (when enabled), then the global user rules, then the current tab's scope rules.
 * The after side is the working tree (HEAD plus uncommitted edits), so a real edit un-hides a file
 * whose base-to-HEAD diff is noise. Adds, deletes, moves, binaries and oversized files are never
 * noise.
 * <p>
 * Background thread only: loading both revision contents is a slow operation.
 */
public class ChangeNoiseClassifier {
    private static final Logger LOG = Defs.getLogger(ChangeNoiseClassifier.class);

    /** Upper bound on cached verdicts: paths accumulate over a session, verdicts must not. */
    private static final int MAX_CACHED_VERDICTS = 4096;

    /* Immutable and swapped atomically whenever the settings' noise-rule version or the scope rules change. */
    private volatile NormalizerSnapshot normalizerSnapshot;

    /* The current tab's scope rules; copied on install so later model edits cannot change the snapshot. */
    private volatile List<NoiseRuleState> scopeRules = List.of();

    /* Bounded FIFO by path: entries for older revisions or rules versions are never read, only evicted. */
    private final Map<String, Verdict> verdictCache = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Verdict> eldest) {
                    return size() > MAX_CACHED_VERDICTS;
                }
            });

    /**
     * Installs the scope rules of the tab currently being collected. Rules equal to the installed
     * ones leave the normaliser snapshot alone; any real change rebuilds it and, through the
     * snapshot's build counter, invalidates the cached verdicts.
     */
    public void setScopeRules(@NotNull List<NoiseRuleState> rules) {
        List<NoiseRuleState> copy = new ArrayList<>(rules.size());
        for (NoiseRuleState rule : rules) {
            copy.add(rule.copy());
        }
        scopeRules = copy;
    }

    /**
     * Whether {@code change} is noise. Verdicts are cached by path while the before revision, the
     * after-side modification stamp and the rules version all match; changes whose after file has
     * no {@link VirtualFile} are compared but not cached.
     */
    public boolean isNoise(@NotNull Change change) {
        Change effectiveChange = ScopeDiff.withWorkingTreeAfterSide(change);
        ContentRevision before = effectiveChange.getBeforeRevision();
        ContentRevision after = effectiveChange.getAfterRevision();
        boolean isPlainModification = effectiveChange.getType() == Change.Type.MODIFICATION
                && before != null && after != null;
        if (!isPlainModification) {
            return false;
        }

        boolean isTextFile = !before.getFile().getFileType().isBinary()
                && !after.getFile().getFileType().isBinary();
        if (!isTextFile) {
            return false;
        }

        VirtualFile afterFile = after.getFile().getVirtualFile();
        boolean afterFileTooBig = afterFile != null && afterFile.getLength() > VcsUtil.getMaxVcsLoadedFileSize();
        if (afterFileTooBig) {
            return false;
        }

        String path = after.getFile().getPath();
        String beforeRevision = before.getRevisionNumber().asString();
        Long afterStamp = afterFile == null ? null : modificationStamp(afterFile);
        NormalizerSnapshot snapshot = snapshot();
        long rulesVersion = snapshot.build();
        if (afterStamp != null) {
            Verdict cached = verdictCache.get(path);
            boolean isCacheHit = cached != null
                    && cached.beforeRevision().equals(beforeRevision)
                    && cached.afterStamp() == afterStamp
                    && cached.rulesVersion() == rulesVersion;
            if (isCacheHit) {
                return cached.noise();
            }
        }

        boolean noise = compareNormalizedTexts(path, before, after, snapshot.normalizer());
        if (afterStamp != null) {
            verdictCache.put(path, new Verdict(beforeRevision, afterStamp, rulesVersion, noise));
        }
        return noise;
    }

    /** Returns {@code changes} without the noise ones, preserving the original order. */
    public @NotNull Collection<Change> withoutNoise(@NotNull Collection<Change> changes) {
        List<Change> kept = new ArrayList<>(changes.size());
        for (Change change : changes) {
            if (!isNoise(change)) {
                kept.add(change);
            }
        }
        return kept;
    }

    /**
     * The normaliser used to compare the two sides of a change; shared with the diff computer.
     * Rebuilt whenever {@link GitScopeSettings#getNoiseRulesVersion()} or the installed scope
     * rules change; verdicts cached under the previous build are simply never hit again.
     */
    public @NotNull NoiseNormalizer normalizer() {
        return snapshot().normalizer();
    }

    /** The current snapshot if it matches the settings version and the scope rules, else a rebuild. */
    private @NotNull NormalizerSnapshot snapshot() {
        NormalizerSnapshot current = normalizerSnapshot;
        if (current != null && current.isCurrent(
                GitScopeSettings.getInstance().getNoiseRulesVersion(), scopeRules)) {
            return current;
        }
        return rebuildNormalizer();
    }

    /** Synchronised so concurrent callers share one rebuild. */
    private synchronized @NotNull NormalizerSnapshot rebuildNormalizer() {
        GitScopeSettings settings = GitScopeSettings.getInstance();
        long settingsVersion = settings.getNoiseRulesVersion();
        List<NoiseRuleState> currentScopeRules = scopeRules;
        NormalizerSnapshot current = normalizerSnapshot;
        if (current != null && current.isCurrent(settingsVersion, currentScopeRules)) {
            return current;
        }
        long build = current == null ? 1 : current.build() + 1;
        NoiseNormalizer normalizer = NoiseRules.fromSettings(settings, currentScopeRules);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Hide Noise: rebuilt normalizer (build " + build + ") with rules: "
                    + normalizer.rules().stream().map(NoiseRule::name).toList());
        }
        NormalizerSnapshot rebuilt = new NormalizerSnapshot(build, settingsVersion, currentScopeRules, normalizer);
        normalizerSnapshot = rebuilt;
        return rebuilt;
    }

    /**
     * A normaliser and the inputs it was built from: the settings version and the scope rules, plus
     * a monotonically increasing build counter that stands in for both in cached verdicts.
     */
    private record NormalizerSnapshot(long build, long settingsVersion,
                                      @NotNull List<NoiseRuleState> scopeRules,
                                      @NotNull NoiseNormalizer normalizer) {

        boolean isCurrent(long settingsVersion, @NotNull List<NoiseRuleState> scopeRules) {
            return this.settingsVersion == settingsVersion && this.scopeRules.equals(scopeRules);
        }
    }

    /**
     * A cached verdict, valid only while its before revision, after-side modification stamp and
     * rules build all still match the change being classified.
     */
    private record Verdict(@NotNull String beforeRevision, long afterStamp, long rulesVersion, boolean noise) {
    }

    private boolean compareNormalizedTexts(@NotNull String path, @NotNull ContentRevision before,
                                           @NotNull ContentRevision after, @NotNull NoiseNormalizer normalizer) {
        String beforeText;
        String afterText;
        try {
            beforeText = before.getContent();
            afterText = after.getContent();
        } catch (VcsException e) {
            LOG.debug("Hide Noise: content unavailable for " + path, e);
            return false;
        }

        boolean hasBothContents = beforeText != null && afterText != null;
        if (!hasBothContents) {
            return false;
        }

        int maxVcsLoadedFileSize = VcsUtil.getMaxVcsLoadedFileSize();
        boolean fitsVcsSizeLimit = beforeText.length() <= maxVcsLoadedFileSize
                && afterText.length() <= maxVcsLoadedFileSize;
        if (!fitsVcsSizeLimit) {
            return false;
        }

        return ComparisonManager.getInstance().isEquals(
                normalizer.normalize(path, beforeText),
                normalizer.normalize(path, afterText),
                NoisePolicy.comparisonPolicyFor(path));
    }

    /**
     * The stamp a verdict is keyed by: a cached document's stamp moves with unsaved edits, so an
     * open file invalidates its verdict even before it is saved.
     */
    private static long modificationStamp(@NotNull VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        return document != null ? document.getModificationStamp() : file.getModificationStamp();
    }
}