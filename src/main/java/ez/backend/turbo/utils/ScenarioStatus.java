package ez.backend.turbo.utils;

// Lifecycle states for a simulation scenario
public enum ScenarioStatus {

    CREATED,
    QUEUED,
    VALIDATING,
    SIMULATING_BASELINE,
    SIMULATING_POLICY,
    POSTPROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
