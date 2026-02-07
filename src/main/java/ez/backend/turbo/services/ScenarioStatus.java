package ez.backend.turbo.services;

// Lifecycle states for a simulation scenario
public enum ScenarioStatus {

    CREATED,
    VALIDATING,
    SIMULATING_BASELINE,
    SIMULATING_POLICY,
    POSTPROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
