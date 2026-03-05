package ez.backend.turbo.validation;

public enum AreaValidationError {
    REQUIRED,
    ID_REQUIRED,
    ID_INVALID_FORMAT,
    COORDS_REQUIRED,
    ZONE_REF_REQUIRED,
    ZONE_REF_INVALID_FORMAT,
    ZONE_REF_NOT_FOUND
}
