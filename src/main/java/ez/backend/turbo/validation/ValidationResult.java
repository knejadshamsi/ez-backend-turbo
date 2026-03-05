package ez.backend.turbo.validation;

import java.util.ArrayList;
import java.util.List;

public final class ValidationResult {

    private final List<ValidationError> errors = new ArrayList<>();

    public void add(String origin, Enum<?> error, String message) {
        errors.add(new ValidationError(origin, error.name(), message));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<ValidationError> errors() {
        return List.copyOf(errors);
    }
}
