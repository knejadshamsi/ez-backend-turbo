package ez.backend.turbo.services;

public record StrategyConfig(
        double changeExpBetaWeight,
        double reRouteWeight,
        double subtourModeChoiceWeight,
        double timeAllocationMutatorWeight,
        String[] subtourModes,
        String[] chainBasedModes,
        double mutationRange,
        int maxAgentPlanMemorySize,
        int globalThreads,
        int qsimThreads
) {

    public StrategyConfig {
        if (changeExpBetaWeight < 0) {
            throw new IllegalArgumentException(
                    "changeExpBetaWeight must be >= 0 | changeExpBetaWeight doit être >= 0");
        }
        if (reRouteWeight < 0) {
            throw new IllegalArgumentException(
                    "reRouteWeight must be >= 0 | reRouteWeight doit être >= 0");
        }
        if (subtourModeChoiceWeight < 0) {
            throw new IllegalArgumentException(
                    "subtourModeChoiceWeight must be >= 0 | subtourModeChoiceWeight doit être >= 0");
        }
        if (timeAllocationMutatorWeight < 0) {
            throw new IllegalArgumentException(
                    "timeAllocationMutatorWeight must be >= 0 | timeAllocationMutatorWeight doit être >= 0");
        }
        if (subtourModes == null || subtourModes.length == 0) {
            throw new IllegalArgumentException(
                    "subtourModes must not be empty | subtourModes ne doit pas être vide");
        }
        if (chainBasedModes == null || chainBasedModes.length == 0) {
            throw new IllegalArgumentException(
                    "chainBasedModes must not be empty | chainBasedModes ne doit pas être vide");
        }
        if (mutationRange <= 0) {
            throw new IllegalArgumentException(
                    "mutationRange must be > 0 | mutationRange doit être > 0");
        }
        if (maxAgentPlanMemorySize < 1) {
            throw new IllegalArgumentException(
                    "maxAgentPlanMemorySize must be >= 1 | maxAgentPlanMemorySize doit être >= 1");
        }
        if (globalThreads < 1) {
            throw new IllegalArgumentException(
                    "globalThreads must be >= 1 | globalThreads doit être >= 1");
        }
        if (qsimThreads < 1) {
            throw new IllegalArgumentException(
                    "qsimThreads must be >= 1 | qsimThreads doit être >= 1");
        }
    }
}
