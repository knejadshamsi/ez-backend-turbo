package ez.backend.turbo.utils;

import org.springframework.stereotype.Component;

@Component
public class ResponseFormatter {

    public <T> StandardResponse<T> success(T payload) {
        return StandardResponse.ok(payload);
    }

    public <T> StandardResponse<T> success(String message, T payload) {
        return StandardResponse.ok(message, payload);
    }

    public StandardResponse<?> error(int statusCode, String message) {
        return StandardResponse.error(statusCode, message);
    }
}
