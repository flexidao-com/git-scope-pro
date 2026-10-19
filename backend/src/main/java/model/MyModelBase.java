package model;

import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import settings.NoiseRuleState;

import java.util.ArrayList;
import java.util.List;

public class MyModelBase {
    public TargetBranchMap targetBranchMap = null;

    public String customTabName;

    /**
     * Noise rules scoped to this tab, applied in addition to the global rules (built-in first,
     * then global, then these). Persisted with the tab through {@code state.MyModelConverter}'s
     * Gson round-trip.
     */
    public List<NoiseRuleState> noiseRules = new ArrayList<>();

    public TargetBranchMap getTargetBranchMap() {
        return targetBranchMap;
    }

    public void setTargetBranchMap(TargetBranchMap targetBranch) {
        this.targetBranchMap = targetBranch;
    }

    public void addTargetBranch(GitRepository repo, String branch) {
        if (targetBranchMap == null) {
            targetBranchMap = TargetBranchMap.create();
        }
        targetBranchMap.add(repo, branch);
    }

    public String getCustomTabName() {
        return customTabName;
    }

    public void setCustomTabName(String customTabName) {
        this.customTabName = customTabName;
    }

    /** This tab's scope rules; never null, even for a tab persisted before they existed. */
    public @NotNull List<NoiseRuleState> getNoiseRules() {
        if (noiseRules == null) {
            noiseRules = new ArrayList<>();
        }
        return noiseRules;
    }

    public void setNoiseRules(@NotNull List<NoiseRuleState> rules) {
        this.noiseRules = new ArrayList<>(rules);
    }
}