package ez.backend.turbo.validation;

public record ValidationError(String origin, String error, String message) {}
