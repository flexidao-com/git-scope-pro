package implementation.gutter;

import implementation.noise.NoiseNormalizer;
import implementation.noise.NoiseRules;
import org.jetbrains.annotations.NotNull;
import settings.GitScopeSettings;
import settings.NoiseRuleState;

import java.util.List;

/**
 * Frontend-side cache of the noise normaliser, rebuilt only when
 * {@link GitScopeSettings#getNoiseRulesVersion()} or the scope rules list changes: the same pattern
 * the backend's {@code ChangeNoiseClassifier} uses, but reading the frontend's own settings copy.
 * <p>
 * Split-mode caveat: application settings are synchronised by the platform, so right after a rules
 * edit the frontend may briefly build from the previous rules.
 */
public final class NoiseNormalizerCache {

    private static volatile long cachedVersion = -1;
    private static volatile List<NoiseRuleState> cachedScopeRules = List.of();
    private static volatile NoiseNormalizer cachedNormalizer;

    private NoiseNormalizerCache() {
    }

    /** The normaliser for the current settings and no scope rules. */
    public static @NotNull NoiseNormalizer get() {
        return get(List.of());
    }

    /**
     * The normaliser for the current settings plus {@code scopeRules}, rebuilding it when either
     * input changed.
     */
    public static @NotNull NoiseNormalizer get(@NotNull List<NoiseRuleState> scopeRules) {
        long version = GitScopeSettings.getInstance().getNoiseRulesVersion();
        List<NoiseRuleState> rules = List.copyOf(scopeRules);
        NoiseNormalizer normalizer = cachedNormalizer;
        if (normalizer != null && cachedVersion == version && cachedScopeRules.equals(rules)) {
            return normalizer;
        }
        return rebuild(version, rules);
    }

    /** Synchronised so concurrent callers share one rebuild. */
    private static synchronized @NotNull NoiseNormalizer rebuild(long version,
                                                                 @NotNull List<NoiseRuleState> scopeRules) {
        NoiseNormalizer normalizer = cachedNormalizer;
        if (normalizer != null && cachedVersion == version && cachedScopeRules.equals(scopeRules)) {
            return normalizer;
        }
        NoiseNormalizer rebuilt = NoiseRules.fromSettings(GitScopeSettings.getInstance(), scopeRules);
        cachedNormalizer = rebuilt;
        cachedVersion = version;
        cachedScopeRules = scopeRules;
        return rebuilt;
    }
}