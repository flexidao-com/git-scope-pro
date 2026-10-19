package rpc

import kotlinx.serialization.Serializable

@Serializable
data class GutterRangeDto(
    val line1: Int,
    val line2: Int,
    val vcsLine1: Int,
    val vcsLine2: Int
)

@Serializable
data class NoiseRuleDto(
    val enabled: Boolean = true,
    val name: String = "",
    val fileGlob: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val literal: Boolean = false
)

@Serializable
data class GutterFileDataDto(
    val filePath: String,
    val ranges: List<GutterRangeDto>,
    /**
     * Full contents of the diff base / HEAD revision. Contents dominate the message size (the
     * ranges are a handful of ints), so the backend includes them only when they changed since the
     * last message for this file within the same subscription; the frontend caches the previous
     * values and reuses them. [contentsIncluded] distinguishes "unchanged, reuse cache" from a
     * genuinely null [headContent].
     */
    val baseContent: String? = null,
    val headContent: String? = null,
    val scopeRanges: List<GutterRangeDto>? = null,
    val scopeDisplayName: String = "",
    val separateGutterRendering: Boolean = false,
    val hideNoise: Boolean = false,
    /**
     * Noise configuration, copied into the frontend's GitScopeSettings on receipt (split mode).
     * The platform syncs application settings too, but with its own timing; carrying the values
     * with the gutter data makes a rules edit take effect on the next publish, exactly like
     * [separateGutterRendering] and [hideNoise]. Defaults match the settings' defaults, so a peer
     * that does not send them leaves the frontend's own values in place.
     */
    val builtInNoiseRulesEnabled: Boolean = true,
    val noiseRules: List<NoiseRuleDto> = emptyList(),
    /**
     * The active tab's scope noise rules, in addition to the global [noiseRules] and the built-in
     * flag. They travel in their own field on purpose: in monolith mode the frontend shares the
     * user's [GitScopeSettings] instance, so merging a tab's rules into [noiseRules] on receipt
     * would overwrite the user's global rules. Empty when the tab defines none.
     */
    val scopeNoiseRules: List<NoiseRuleDto> = emptyList(),
    val contentsIncluded: Boolean = true
)
