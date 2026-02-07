package ez.backend.turbo.services;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.RandomBasedGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

// UUID v4 generator for scenario request identifiers
@Component
public class IdGenerator {

    private final RandomBasedGenerator generator = Generators.randomBasedGenerator();

    public UUID generate() {
        return generator.generate();
    }
}
