# Noise filter roadmap

Goal: make code review in the Git Scope tool window less repetitive by hiding
changes that are mechanical — import-only edits, whitespace/reformatting,
snapshot values that change with a fixed pattern, refactors already reviewed.

## Model: normalise, then compare

Inspired by the `diffstripper` tool (replace patterns; a line disappears from
the diff when, after applying the replacements, the source line equals the
target line).

Every rule is a `(file glob?, regex, replacement)` triple. The regex runs in
MULTILINE mode over the whole file text, so a rule can swallow a multi-line
`import { … } from '…'` block as easily as a single token. Both sides of a
change are normalised with the same rules; a file is **noise** when the two
normalised texts are equal under IntelliJ's `IGNORE_WHITESPACES` comparison.

This beats "ignore lines matching a regex" because it also covers:

- renames (`oldName → newName`) — rule `oldName → newName`
- snapshot timestamps/ids — rule `"updatedAt": "[^"]*" → "updatedAt": "X"`
- import churn in languages IntelliJ has no import-aware provider for (TS/JS)

### Why not the platform's "Ignore imports and formatting"?

That diff-viewer option is backed by `DiffIgnoredRangeProvider`, and IntelliJ
ships implementations only for Java and Kotlin. For TypeScript no provider
accepts the content, so the viewer falls back to the generic "Ignore
formatting" label and ignores whitespace only. The comparison entry points
that take ignored ranges (`ComparisonManagerImpl.compareLinesWithIgnoredRanges`)
are `@ApiStatus.Internal`. The file-level verdict therefore uses only public
API: `ComparisonManager.isEquals(text1, text2, ComparisonPolicy.IGNORE_WHITESPACES)`
on normalised text.

## Phases

### Phase 1 — "Hide noise" toggle with built-in rules  (done, pending manual IDE check)

- `implementation.noise.NoiseRule` / `NoiseNormalizer`: pure, unit-tested
  normalisation engine with built-in import rules for Java, Kotlin, JS/TS,
  Python and Go.
- `implementation.noise.ChangeNoiseClassifier` (project service, background
  thread only): loads both `ContentRevision`s of a `MODIFICATION` change, skips
  binaries and oversized files, returns a cached verdict.
- Toolbar toggle **Hide Noise** next to *Show Deleted / Show Untracked*
  (per-project `State.hideNoise`, app default in `GitScopeSettings`, seeded in
  `ViewService.seedWorkingTreeDefaults`).
- Filter applied in `ViewService.collectChangesInternal` on the collection
  executor, before the change maps are built. Only the tree is filtered; file
  colours and gutter markers keep showing every change.
- `implementation.noise.NoiseDiffComputer` (backend): `ScopeDiff.buildProducer`
  attaches it as `DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER` when Hide Noise is
  on, so diffs opened from Git Scope normalise both sides keeping lines (every
  noise match blanked in place), compare the normalised texts, drop fragments
  whose sides are equal under `IGNORE_WHITESPACES`, then remap the kept
  fragments onto the original texts (word-level inner fragments only where no
  replacement happened). A rule that adds lines makes the computer fall back
  to the unfiltered diff. Setting the computer removes the viewer's "Ignore
  imports and formatting" option.

  - `OpenDiffsRefresher.rediffAll`: refreshes already-open diff viewers (both
    editor tabs and floating `DiffWindowBase` dialogs) when global or scope
    noise rules change, by traversing showing `Window`s and open `FileEditor`
    components with `UIUtil.uiTraverser`, reading `DiffDataKeys.DIFF_VIEWER`
    (and guarding `CommonDataKeys.PROJECT`) from each `UiDataProvider` /
    `DataProvider` component's `DataContext`, and calling
    `DiffViewerBase.rediff(true)`.

  Known limits:

  - A noise line adjacent to a real change may stay highlighted inside the
    same fragment.
  - User rules must not add lines.

### Phase 2 — User-defined rules (done)

- Settings → Tools → Git Scope → *Noise rules*: checkbox for the built-in
  import rules plus a table of user rules (enabled, name, file glob, regex,
  replacement) stored application-wide in `GitScopeSettings.noiseRules`
  (`NoiseRuleState` beans). Export…/Import… as JSON for sharing.
- `NoiseRules.fromSettings` builds the normaliser (built-in first, then enabled
  user rules; a rule that fails to compile is skipped with a warning).
  `ChangeNoiseClassifier` rebuilds it when `getNoiseRulesVersion()` changes and
  clears its verdict cache; applying the settings page refreshes open projects.
- The diff-viewer dimming via `DiffIgnoredRangeProvider` originally planned
  here was dropped: `NoiseDiffComputer` (Phase 1) already removes noise from
  the diff instead of dimming it, works for every language, and uses public
  API only.

### Phase 3 — "Mark as reviewed" (done)

- Context actions **Mark as Reviewed** / **Unmark Reviewed** / **Clear All
  Reviewed Marks (N)**. `ReviewedChangesService` stores a SHA-256 of the
  normalised before/after texts per file path in the workspace
  (`State.reviewedChanges`); while the hash matches, the file is hidden
  together with the noise when Hide Noise is on, so later import churn keeps
  it hidden and any real edit brings it back.
- Marks are workspace-only and never pruned automatically; use *Clear All*.

### Also done

- Setting *Double-click on a file opens its diff instead of the file*
  (`GitScopeSettings.doubleClickOpensDiff`, default off).

### Phase 4 — Gutter (done)

- Gutter markers skip noise changes: `RangesBuilder.createRanges` takes the
  normaliser and the file path, normalises both sides keeping lines, and drops
  fragments whose normalised sides are whitespace-equal (same rule as
  `NoiseDiffComputer`); when a rule changes the line count it falls back to the
  raw texts. Scope ranges and the current-space ranges are filtered; the local
  ranges that map HEAD→current stay raw, because the platform still paints
  local markers and the mapping needs every line shift.
- `GutterFileData` carries a per-file `hideNoise` flag (backend → RPC →
  frontend, like `separateGutterRendering`), so the frontend's live recompute on
  edits applies the rules and the gutter popup attaches `NoiseDiffComputer`.
- The frontend rebuilds the normaliser from its own `GitScopeSettings` copy
  (`NoiseNormalizerCache`, keyed by `getNoiseRulesVersion()`); in split mode the
  gutter DTO carries the built-in flag and the user rules (like
  `separateGutterRendering` and `hideNoise`), and the frontend writes them into
  its copy only when they differ, so a rules edit takes effect on the next
  publish instead of waiting for the platform's settings synchronisation.

### Phase 5 — Scope rules (done)

- Per-tab rules, applied **in addition** to the global ones wherever Hide Noise
  acts (tree, diff, gutter, popup). Precedence: built-in (if enabled) →
  global enabled rules → the tab's enabled rules; same skipping and
  coalescing, so a bad scope rule cannot disable detection.
- Storage: `MyModelBase.noiseRules`, per tab in `State.modelData` through
  `MyModelConverter`'s Gson round-trip. `ViewService.load()`/`save()` copy them
  on and off the live `MyModel` exactly like `customTabName`;
  `ViewService.updateCurrentScopeNoiseRules` (EDT) replaces, persists and
  re-collects.
- `ChangeNoiseClassifier` installs the tab's rules via `setScopeRules` whenever
  `ViewService.collectChanges(model, …)` starts — the single entry both tab
  switches and refreshes go through. The normaliser snapshot is keyed by the
  settings version **and** the scope rules; it carries a monotonically
  increasing build counter, which stands in for both in cached verdicts, so a
  scope-rules edit invalidates them without touching the global settings.
- Gutter: the rules ride along `GutterFileData.scopeNoiseRules` → RPC → the
  frontend's `GutterFileData`, and the live recompute and `ScopeDiffViewer`
  build their normaliser with `NoiseNormalizerCache.get(scopeRules)`, keyed by
  the settings version plus the scope list. Scope rules deliberately do **not**
  join `FrontendGutterListeners.syncNoiseRules`: in monolith mode the frontend
  shares the user's `GitScopeSettings` instance, so writing scope rules into its
  `noiseRules` there would overwrite the global ones.

### Pending inputs

- Look at the user's `diffstripper` rule format and examples; align the rule
  syntax (and offer an importer) if it fits.
- Decide whether reviewed marks should be shareable (file in repo) or stay
  workspace-only.

## Platform notes gathered during research

- `IgnorePolicy.FORMATTING` label: `SmartTextDiffProvider.getText()` returns
  `provider.getDescription()` of the first `DiffIgnoredRangeProvider` accepting
  **both** sides, else falls back to `DiffBundle` "Ignore formatting".
- EP `com.intellij.diff.lang.DiffIgnoredRangeProvider` (dynamic). Semantics are
  first-match, not union — use `order="first"` and delegate explicitly.
- `platform/vcs-impl/.../ChangesFilterer.kt` (`NonImportantFilter`) is the
  reference for "hide changes that are empty after ignoring"; it is internal
  and Java/Kotlin only.
- IntelliJ reads repo content with `git cat-file --filters` by default; git
  `textconv` drivers are honoured only when the hidden advanced setting
  `git.read.content.with` is `TEXTCONV`, and never for the working-tree side.
