package org.mobility.utils;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedGenerator;

public class IdGenerator {
    private static final TimeBasedGenerator timeBasedGenerator = Generators.timeBasedGenerator();

    public static String generateId() {
        return timeBasedGenerator.generate().toString();
    }
}
