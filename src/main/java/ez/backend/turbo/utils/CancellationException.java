package ez.backend.turbo.utils;

import java.util.UUID;

public class CancellationException extends RuntimeException {

    public CancellationException(UUID requestId) {
        super(requestId.toString());
    }
}
