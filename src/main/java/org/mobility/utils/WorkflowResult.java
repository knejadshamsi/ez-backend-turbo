package org.mobility.utils;

import java.util.Map;
import java.util.HashMap;

public class WorkflowResult {
    private final boolean success;
    private final int code;
    private final String message;
    private final Map<String, Object> data;

    private WorkflowResult(boolean success, int code, String message, Map<String, Object> data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data != null ? data : new HashMap<>();
    }

    public static WorkflowResult success(Map<String, Object> data) {
        return new WorkflowResult(true, 200, "Success", data);
    }

    public static WorkflowResult error(int code, String message) {
        return new WorkflowResult(false, code, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return new HashMap<>(data);
    }
}
