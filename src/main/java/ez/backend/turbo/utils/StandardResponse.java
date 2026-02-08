package ez.backend.turbo.utils;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

public class StandardResponse<T> {

    @JsonProperty("statusCode")
    private final int statusCode;

    @JsonProperty("message")
    private final String message;

    @JsonProperty("payload")
    private final T payload;

    @JsonProperty("timestamp")
    private final String timestamp;

    private StandardResponse(int statusCode, String message, T payload) {
        this.statusCode = statusCode;
        this.message = message;
        this.payload = payload;
        this.timestamp = Instant.now().toString();
    }

    public static <T> StandardResponse<T> ok(String message, T payload) {
        return new StandardResponse<>(200, message, payload);
    }

    public static <T> StandardResponse<T> ok(T payload) {
        return new StandardResponse<>(200, "OK", payload);
    }

    public static StandardResponse<?> error(int statusCode, String message) {
        return new StandardResponse<>(statusCode, message, Map.of());
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getMessage() {
        return message;
    }

    public T getPayload() {
        return payload;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
