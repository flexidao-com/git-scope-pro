package implementation.noise;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import state.State;
import system.Defs;
import utils.ScopeDiff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Remembers which files the user marked as reviewed, as a SHA-256 of each file's normalised
 * base-to-working-tree diff at the moment it was marked. A change is reviewed while its current
 * diff still hashes to the stored value; any real edit, committed or still uncommitted, changes
 * the hash and brings the file back. The tree hides reviewed changes when Hide Noise is on.
 * <p>
 * {@link #isReviewed} (and through it {@link #withoutReviewed}) and {@link #markReviewed} load
 * revision contents, so they are background-thread only.
 */
public final class ReviewedChangesService {

    private static final Logger LOG = Defs.getLogger(ReviewedChangesService.class);

    private final Project project;

    public ReviewedChangesService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Whether {@code change} is reviewed: its path has a mark and the change still hashes to it.
     * Background thread only.
     */
    public boolean isReviewed(@NotNull Change change) {
        String path = pathOf(change);
        return path != null && hashMatchesMark(path, change);
    }

    /** Returns {@code changes} without the reviewed ones, preserving the original order. */
    public @NotNull Collection<Change> withoutReviewed(@NotNull Collection<Change> changes) {
        List<Change> kept = new ArrayList<>(changes.size());
        for (Change change : changes) {
            if (!isReviewed(change)) {
                kept.add(change);
            }
        }
        return kept;
    }

    /** Whether {@code path} has a reviewed mark. Cheap and EDT-safe: no content is loaded. */
    public boolean hasMark(@NotNull String path) {
        return marks().containsKey(path);
    }

    /**
     * Whether {@code change}'s stored path has a reviewed mark. Cheap and EDT-safe: no content is
     * loaded.
     */
    public boolean hasMark(@NotNull Change change) {
        String path = pathOf(change);
        return path != null && hasMark(path);
    }

    /**
     * Marks every change whose hash can be computed. Background thread only: loads the revision
     * contents. Changes whose content is unavailable are skipped.
     */
    public void markReviewed(@NotNull Collection<Change> changes) {
        Map<String, String> marks = marks();
        for (Change change : changes) {
            String path = pathOf(change);
            if (path == null) {
                continue;
            }
            String hash = computeHash(path, change);
            if (hash == null) {
                continue;
            }
            marks.put(path, hash);
        }
    }

    /** Removes the marks of {@code changes}, so they show up again. */
    public void unmarkReviewed(@NotNull Collection<Change> changes) {
        Map<String, String> marks = marks();
        for (Change change : changes) {
            String path = pathOf(change);
            if (path != null) {
                marks.remove(path);
            }
        }
    }

    /** Removes every reviewed mark in the project. */
    public void clearAllMarks() {
        marks().clear();
    }

    /** How many reviewed marks the project currently has. Cheap and EDT-safe. */
    public int markCount() {
        return marks().size();
    }

    /** Whether the stored mark for {@code path} still matches the change's current hash. */
    private boolean hashMatchesMark(@NotNull String path, @NotNull Change change) {
        Map<String, String> marks = marks();
        String mark = marks.get(path);
        if (mark == null) {
            return false;
        }
        String currentHash = computeHash(path, change);
        if (mark.equals(currentHash)) {
            return true;
        }
        /* The file changed since it was marked, so it shows again. Dropping the stale mark also
         * stops a later revert to the marked content from hiding it once more. */
        marks.remove(path, mark);
        return false;
    }

    /**
     * SHA-256 of both normalised sides joined by a NUL, or null when a side's content is missing.
     * A side without a revision (added/deleted file) contributes an empty text, so import churn
     * after marking does not un-review a file while a real edit does.
     */
    private @Nullable String computeHash(@NotNull String path, @NotNull Change change) {
        Change effectiveChange = ScopeDiff.withWorkingTreeAfterSide(change);
        ContentRevision before = effectiveChange.getBeforeRevision();
        ContentRevision after = effectiveChange.getAfterRevision();
        String beforeText;
        String afterText;
        try {
            beforeText = before == null ? "" : before.getContent();
            afterText = after == null ? "" : after.getContent();
        } catch (VcsException e) {
            LOG.debug("Reviewed marks: content unavailable for " + path, e);
            return null;
        }

        boolean hasBothContents = beforeText != null && afterText != null;
        if (!hasBothContents) {
            return null;
        }

        NoiseNormalizer normalizer = project.getService(ChangeNoiseClassifier.class).normalizer();
        String fingerprint = normalizer.normalize(path, beforeText)
                + "\u0000"
                + normalizer.normalize(path, afterText);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the Java platform", e);
        }
    }

    /** The path a mark is stored under: the after revision's, or the before one's for deletions. */
    private static @Nullable String pathOf(@NotNull Change change) {
        ContentRevision after = change.getAfterRevision();
        if (after != null) {
            return after.getFile().getPath();
        }
        ContentRevision before = change.getBeforeRevision();
        return before != null ? before.getFile().getPath() : null;
    }

    private @NotNull Map<String, String> marks() {
        return project.getService(State.class).getReviewedChanges();
    }
}