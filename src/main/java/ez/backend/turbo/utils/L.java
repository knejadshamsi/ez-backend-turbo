package ez.backend.turbo.utils;

import ez.backend.turbo.config.StartupValidator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class L {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private static final List<String> LOCALE_FILES = List.of(
            "config", "db", "scenario", "simulation", "source", "sse", "system", "validation");

    private static volatile Map<String, String> messages = Map.of();

    private final String locale;
    private final ResourceLoader resourceLoader;

    public L(StartupValidator startupValidator,
             ResourceLoader resourceLoader) {
        this.locale = startupValidator.getLocale();
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> merged = new HashMap<>();
        for (String file : LOCALE_FILES) {
            Resource resource = resourceLoader.getResource(
                    "classpath:locale/" + locale + "/" + file + ".json");
            Map<String, String> fileMessages = mapper.readValue(resource.getInputStream(), MAP_TYPE);
            for (Map.Entry<String, String> entry : fileMessages.entrySet()) {
                String existing = merged.put(entry.getKey(), entry.getValue());
                if (existing != null) {
                    throw new IllegalStateException("Duplicate locale key: " + entry.getKey()
                            + " | Clé de locale dupliquée : " + entry.getKey());
                }
            }
        }
        messages = Map.copyOf(merged);
    }

    public static String msg(String key) {
        String message = messages.get(key);
        if (message == null) {
            throw new IllegalArgumentException("Missing locale key: " + key
                    + " | Clé de locale manquante : " + key);
        }
        return message;
    }
}
