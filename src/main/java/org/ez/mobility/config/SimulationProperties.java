package org.ez.mobility.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Configuration
@ConfigurationProperties(prefix = "simulation")
@Validated
public class SimulationProperties {
    @NotNull
    private String configPath;
    
    @NotNull
    private String outputPath;
    
    private Cleanup cleanup = new Cleanup();
    
    @Min(100)
    private int batchSize = 1000;
    
    @Min(1)
    private int maxConcurrent = 4;
    
    @Min(1)
    private int timeoutMinutes = 120;
    
    private Transit transit = new Transit();

    public static class Cleanup {
        private boolean enabled = true;
        
        @Min(1)
        private int ageHours = 24;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getAgeHours() {
            return ageHours;
        }

        public void setAgeHours(int ageHours) {
            this.ageHours = ageHours;
        }
    }

    public static class Transit {
        @Min(0)
        private double maxWalkDistance = 1000.0;
        
        @Min(0)
        private double searchRadius = 1000.0;
        
        @Min(0)
        private double extensionRadius = 500.0;
        
        private double ptConstant = -1.0;
        private double ptUtility = -6.0;
        private double walkConstant = -3.0;
        private double walkUtility = -12.0;

        public double getMaxWalkDistance() {
            return maxWalkDistance;
        }

        public void setMaxWalkDistance(double maxWalkDistance) {
            this.maxWalkDistance = maxWalkDistance;
        }

        public double getSearchRadius() {
            return searchRadius;
        }

        public void setSearchRadius(double searchRadius) {
            this.searchRadius = searchRadius;
        }

        public double getExtensionRadius() {
            return extensionRadius;
        }

        public void setExtensionRadius(double extensionRadius) {
            this.extensionRadius = extensionRadius;
        }

        public double getPtConstant() {
            return ptConstant;
        }

        public void setPtConstant(double ptConstant) {
            this.ptConstant = ptConstant;
        }

        public double getPtUtility() {
            return ptUtility;
        }

        public void setPtUtility(double ptUtility) {
            this.ptUtility = ptUtility;
        }

        public double getWalkConstant() {
            return walkConstant;
        }

        public void setWalkConstant(double walkConstant) {
            this.walkConstant = walkConstant;
        }

        public double getWalkUtility() {
            return walkUtility;
        }

        public void setWalkUtility(double walkUtility) {
            this.walkUtility = walkUtility;
        }
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public void setCleanup(Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public Transit getTransit() {
        return transit;
    }

    public void setTransit(Transit transit) {
        this.transit = transit;
    }
}
