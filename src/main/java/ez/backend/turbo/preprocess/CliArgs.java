package ez.backend.turbo.preprocess;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class CliArgs {

    private final Map<String, String> values;
    private final Set<String> flags;

    private CliArgs(Map<String, String> values, Set<String> flags) {
        this.values = values;
        this.flags = flags;
    }

    static CliArgs parse(String[] args, Set<String> valuedKeys, Set<String> flagKeys) {
        Map<String, String> values = new HashMap<>();
        Set<String> flags = new HashSet<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException(
                        "Unexpected argument: " + arg + " | Argument inattendu : " + arg);
            }
            if (flagKeys.contains(arg)) {
                flags.add(arg);
            } else if (valuedKeys.contains(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "Missing value for " + arg + " | Valeur manquante pour " + arg);
                }
                values.put(arg, args[++i]);
            } else {
                throw new IllegalArgumentException(
                        "Unknown option: " + arg + " | Option inconnue : " + arg);
            }
        }

        return new CliArgs(values, flags);
    }

    String require(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    key + " is required | " + key + " est requis");
        }
        return value;
    }

    String optional(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    boolean hasFlag(String key) {
        return flags.contains(key);
    }
}
