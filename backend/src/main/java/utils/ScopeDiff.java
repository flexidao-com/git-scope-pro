package utils;

import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import implementation.noise.ChangeNoiseClassifier;
import implementation.noise.NoiseDiffComputer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import state.State;

import java.util.Map;

/**
 * Builds the Git Scope diff request producer for a change: base scope revision (decorated with the
 * active scope name) on the left vs. the current working-tree content on the right.
 *
 * <p>Shared by the changes browser (right-click "Show Diff") and the Show Diff keyboard action so
 * both present exactly the same diff.
 */
public final class ScopeDiff {

    private ScopeDiff() {
    }

    /**
     * Builds a diff producer showing {@code change}'s base revision vs. the current working content.
     * Returns null when the change has no usable file path.
     */
    public static @Nullable ChangeDiffRequestChain.Producer buildProducer(@NotNull Project project,
                                                                          @NotNull Change change,
                                                                          @NotNull String scopeDisplayName) {
        FilePath filePath = ChangesUtil.getAfterPath(change);
        if (filePath == null) {
            filePath = ChangesUtil.getBeforePath(change);
        }
        if (filePath == null || filePath.getVirtualFile() == null) {
            return null;
        }
        Change modifiedChange = withWorkingTreeAfterSide(new Change(
                withScopeName(change.getBeforeRevision(), scopeDisplayName),
                change.getAfterRevision()
        ));
        boolean hideNoise = Boolean.TRUE.equals(project.getService(State.class).getHideNoise());
        if (!hideNoise) {
            return ChangeDiffRequestProducer.create(project, modifiedChange);
        }
        /* The custom computer hides noise-only fragments (imports, whitespace, user rule matches)
         * from every viewer of this request. */
        ChangeNoiseClassifier classifier = project.getService(ChangeNoiseClassifier.class);
        Map<Key<?>, Object> diffContext = Map.of(
                DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER,
                new NoiseDiffComputer(classifier::normalizer, filePath.getPath())
        );
        return ChangeDiffRequestProducer.create(project, modifiedChange, diffContext);
    }

    /**
     * Returns {@code change} with its after side replaced by the current working-tree content, so
     * comparisons use the file as it is now (HEAD plus uncommitted edits) instead of the after
     * revision's stored content. Returns {@code change} unchanged when there is no after file on
     * disk (e.g. a deletion).
     */
    public static @NotNull Change withWorkingTreeAfterSide(@NotNull Change change) {
        FilePath afterPath = ChangesUtil.getAfterPath(change);
        if (afterPath == null || afterPath.getVirtualFile() == null) {
            return change;
        }
        return new Change(change.getBeforeRevision(), new CurrentContentRevision(afterPath));
    }

    /** Decorates a revision's displayed number with the scope name, e.g. "abc1234 (master)". */
    public static @Nullable ContentRevision withScopeName(@Nullable ContentRevision original, @NotNull String scopeName) {
        if (original == null || scopeName.isEmpty()) return original;
        return new ContentRevision() {
            @Override public @Nullable String getContent() throws VcsException { return original.getContent(); }
            @Override public @NotNull FilePath getFile() { return original.getFile(); }
            @Override public @NotNull VcsRevisionNumber getRevisionNumber() {
                VcsRevisionNumber base = original.getRevisionNumber();
                return new VcsRevisionNumber() {
                    @Override public @NotNull String asString() { return base.asString() + " (" + scopeName + ")"; }
                    @Override public int compareTo(@NotNull VcsRevisionNumber o) { return base.compareTo(o); }
                };
            }
        };
    }
}
