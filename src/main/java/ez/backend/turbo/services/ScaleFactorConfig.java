package ez.backend.turbo.services;

public record ScaleFactorConfig(
        double walk, double bike, double car,
        double ev, double subway, double bus
) {}
