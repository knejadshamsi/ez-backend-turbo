package ez.backend.turbo.services;

public record ProcessConfig(int max, long timeoutMs) {

    public ProcessConfig {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0 | max doit être >= 0");
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("timeout must be >= 1 | timeout doit être >= 1");
        }
    }
}
