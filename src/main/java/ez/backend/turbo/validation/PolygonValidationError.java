package ez.backend.turbo.validation;

public enum PolygonValidationError {
    TOO_FEW_POINTS,
    INVALID_POINT_SIZE,
    NOT_CLOSED,
    AREA_TOO_SMALL,
    AREA_TOO_LARGE,
    OUTSIDE_BOUNDARY
}
