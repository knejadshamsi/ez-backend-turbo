package org.ez.mobility.core;

import org.ez.mobility.api.SimulationRequest;
import org.ez.mobility.ez.ZeroEmissionZoneConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class InputManager {
    private static final Logger logger = LoggerFactory.getLogger(InputManager.class);
    private static final String RESOURCES_PATH = "src/main/resources/matsim-input";
    private static final List<String> REQUIRED_FILES = Arrays.asList(
        "network.xml",
        "population.xml",
        "vehicle_types.xml",
        "config.xml"
    );
    private static final List<String> PT_FILES = Arrays.asList(
        "pt/transitSchedule.xml",
        "pt/transitVehicles.xml"
    );
    
    public void prepareSimulation(String requestId) throws IOException {
        logger.info("Preparing simulation input files for request: {}", requestId);
        
        try {
            // Create simulation directory
            Path simDir = createSimulationDirectory(requestId);
            
            // Copy default templates
            copyDefaultTemplates(simDir);
            
            // Copy PT files
            copyPtFiles(simDir);
            
            logger.info("Simulation input preparation completed for request: {}", requestId);
        } catch (IOException e) {
            logger.error("Failed to prepare simulation for request: " + requestId, e);
            throw new IOException("Failed to prepare simulation: " + e.getMessage(), e);
        }
    }

    private Path createSimulationDirectory(String requestId) throws IOException {
        Path simDir = Paths.get("simulations", requestId);
        try {
            Files.createDirectories(simDir);
            Files.createDirectories(simDir.resolve("pt"));
            logger.debug("Created simulation directories: {}", simDir);
            return simDir;
        } catch (IOException e) {
            throw new IOException("Failed to create simulation directory: " + e.getMessage(), e);
        }
    }

    private void copyDefaultTemplates(Path simDir) throws IOException {
        for (String filename : REQUIRED_FILES) {
            try {
                copyResource(filename, simDir);
            } catch (IOException e) {
                throw new IOException("Failed to copy template file " + filename + ": " + e.getMessage(), e);
            }
        }
    }

    private void copyPtFiles(Path simDir) throws IOException {
        Path ptDir = simDir.resolve("pt");
        if (!Files.exists(ptDir)) {
            Files.createDirectories(ptDir);
        }
        
        for (String ptFile : PT_FILES) {
            try {
                Path source = Paths.get(RESOURCES_PATH, ptFile);
                Path target = simDir.resolve(ptFile);
                Files.copy(source, target);
                logger.debug("Copied PT file {} to {}", source, target);
            } catch (IOException e) {
                throw new IOException("Failed to copy PT file " + ptFile + ": " + e.getMessage(), e);
            }
        }
    }

    private void copyResource(String filename, Path targetDir) throws IOException {
        Path source = Paths.get(RESOURCES_PATH, filename);
        Path target = targetDir.resolve(filename);
        
        if (!Files.exists(source)) {
            throw new IOException("Source file does not exist: " + source);
        }
        
        try {
            Files.copy(source, target);
            logger.debug("Copied {} to {}", source, target);
        } catch (IOException e) {
            throw new IOException("Failed to copy file: " + e.getMessage(), e);
        }
    }

    public void validateSimulationFiles(String requestId) throws IOException {
        Path simDir = Paths.get("simulations", requestId);
        
        // Validate required files
        for (String filename : REQUIRED_FILES) {
            Path file = simDir.resolve(filename);
            if (!Files.exists(file)) {
                throw new IOException("Required file missing: " + file);
            }
        }
        
        // Validate PT files
        for (String ptFile : PT_FILES) {
            Path file = simDir.resolve(ptFile);
            if (!Files.exists(file)) {
                throw new IOException("Required PT file missing: " + file);
            }
        }
        
        logger.info("All required simulation files validated for request: {}", requestId);
    }
}
