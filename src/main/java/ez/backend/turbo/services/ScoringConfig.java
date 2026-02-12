package ez.backend.turbo.services;

public record ScoringConfig(
        double performingUtilsPerHr,
        double marginalUtilityOfMoney,
        double brainExpBeta,
        double learningRate,
        double carMarginalUtilityOfTraveling,
        double carMonetaryDistanceRate,
        double ptMarginalUtilityOfTraveling,
        double walkMarginalUtilityOfTraveling,
        double bikeMarginalUtilityOfTraveling
) {

    public ScoringConfig {
        if (performingUtilsPerHr <= 0) {
            throw new IllegalArgumentException(
                    "performingUtilsPerHr must be > 0 | performingUtilsPerHr doit être > 0");
        }
        if (marginalUtilityOfMoney <= 0) {
            throw new IllegalArgumentException(
                    "marginalUtilityOfMoney must be > 0 | marginalUtilityOfMoney doit être > 0");
        }
        if (brainExpBeta <= 0) {
            throw new IllegalArgumentException(
                    "brainExpBeta must be > 0 | brainExpBeta doit être > 0");
        }
        if (learningRate <= 0) {
            throw new IllegalArgumentException(
                    "learningRate must be > 0 | learningRate doit être > 0");
        }
    }
}
