package implementation.noise;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.Component;
import java.awt.Window;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Forces already-open diff viewers — both editor tabs and floating diff windows — to recompute
 * their fragments after the global or per-scope noise rules change. Each request's
 * {@link NoiseDiffComputer} resolves its {@link NoiseNormalizer} through a supplier, so a rediff on
 * the active viewer is enough to pick up the rebuilt normaliser.
 */
public final class OpenDiffsRefresher {

    private OpenDiffsRefresher() {
    }

    /** Re-runs diff computation on every open diff viewer of {@code project}. Must be called on the EDT. */
    public static void rediffAll(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        /* DiffWindowBase keeps its DiffRequestProcessor and WindowWrapper in private/protected
           fields, but every DiffRequestProcessor roots its UI in DiffRequestProcessor$MyPanel (a
           UiDataProvider), whose uiDataSnapshot publishes CommonDataKeys.PROJECT and—through
           DiffRequestProcessor$DefaultState—DiffDataKeys.DIFF_VIEWER. Walking both showing AWT
           windows (floating dialogs and frames) and FileEditorManager's editor components (including
           unselected diff tabs) finds every active DiffViewerBase so rediff(true) can recompute
           fragments in place. */
        Set<DiffViewerBase> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                collectDiffViewers(window, project, viewers);
            }
        }
        for (FileEditor editor : FileEditorManager.getInstance(project).getAllEditors()) {
            collectDiffViewers(editor.getComponent(), project, viewers);
        }
        for (DiffViewerBase viewer : viewers) {
            if (!viewer.isDisposed()) {
                viewer.rediff(true);
            }
        }
    }

    private static void collectDiffViewers(@NotNull Component root,
                                           @NotNull Project project,
                                           @NotNull Set<DiffViewerBase> viewers) {
        DataManager dataManager = DataManager.getInstance();
        for (Component component : UIUtil.uiTraverser(root)) {
            boolean providesUiData = component instanceof UiDataProvider || component instanceof DataProvider;
            if (!providesUiData) {
                continue;
            }
            DataContext context = dataManager.getDataContext(component);
            FrameDiffTool.DiffViewer viewer = DiffDataKeys.DIFF_VIEWER.getData(context);
            if (viewer instanceof DiffViewerBase viewerBase
                    && CommonDataKeys.PROJECT.getData(context) == project) {
                viewers.add(viewerBase);
            }
        }
    }
}
