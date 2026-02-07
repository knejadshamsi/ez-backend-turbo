package ez.backend.turbo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class L {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private static volatile Map<String, String> messages = Map.of();

    private final String locale;
    private final ResourcePatternResolver resolver;

    public L(StartupValidator startupValidator,
             ResourcePatternResolver resolver) {
        this.locale = startupValidator.getLocale();
        this.resolver = resolver;
    }

    @PostConstruct
    void init() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> merged = new HashMap<>();
        Resource[] resources = resolver.getResources("classpath:locale/" + locale + "/*.json");
        for (Resource resource : resources) {
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
            throw new IllegalArgumentException("Missing locale key: " + key);
        }
        return message;
    }
}
