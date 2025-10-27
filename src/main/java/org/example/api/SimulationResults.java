package org.example.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;

public class SimulationResults {
    @JsonProperty("trafficFlow")
    private List<TrafficFlowData> trafficFlow;

    @JsonProperty("emissions")
    private List<EmissionData> emissions;

    @JsonProperty("accessibility")
    private List<AccessibilityData> accessibility;

    @JsonProperty("timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    // Nested classes for specific data types
    public static class TrafficFlowData {
        private String linkId;
        private double volume;
        private double speed;
        private LocalDateTime timeStamp;

        // Getters and setters
        public String getLinkId() { return linkId; }
        public void setLinkId(String linkId) { this.linkId = linkId; }
        public double getVolume() { return volume; }
        public void setVolume(double volume) { this.volume = volume; }
        public double getSpeed() { return speed; }
        public void setSpeed(double speed) { this.speed = speed; }
        public LocalDateTime getTimeStamp() { return timeStamp; }
        public void setTimeStamp(LocalDateTime timeStamp) { this.timeStamp = timeStamp; }
    }

    public static class EmissionData {
        private String vehicleId;
        private double co2;
        private double nox;
        private double pm;
        private LocalDateTime timeStamp;

        // Getters and setters
        public String getVehicleId() { return vehicleId; }
        public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
        public double getCo2() { return co2; }
        public void setCo2(double co2) { this.co2 = co2; }
        public double getNox() { return nox; }
        public void setNox(double nox) { this.nox = nox; }
        public double getPm() { return pm; }
        public void setPm(double pm) { this.pm = pm; }
        public LocalDateTime getTimeStamp() { return timeStamp; }
        public void setTimeStamp(LocalDateTime timeStamp) { this.timeStamp = timeStamp; }
    }

    public static class AccessibilityData {
        private String zoneId;
        private double accessibilityIndex;
        private int publicTransportConnections;
        private double averageWalkingDistance;

        // Getters and setters
        public String getZoneId() { return zoneId; }
        public void setZoneId(String zoneId) { this.zoneId = zoneId; }
        public double getAccessibilityIndex() { return accessibilityIndex; }
        public void setAccessibilityIndex(double accessibilityIndex) { this.accessibilityIndex = accessibilityIndex; }
        public int getPublicTransportConnections() { return publicTransportConnections; }
        public void setPublicTransportConnections(int publicTransportConnections) { this.publicTransportConnections = publicTransportConnections; }
        public double getAverageWalkingDistance() { return averageWalkingDistance; }
        public void setAverageWalkingDistance(double averageWalkingDistance) { this.averageWalkingDistance = averageWalkingDistance; }
    }

    // Getters and setters for main class
    public List<TrafficFlowData> getTrafficFlow() { return trafficFlow; }
    public void setTrafficFlow(List<TrafficFlowData> trafficFlow) { this.trafficFlow = trafficFlow; }
    public List<EmissionData> getEmissions() { return emissions; }
    public void setEmissions(List<EmissionData> emissions) { this.emissions = emissions; }
    public List<AccessibilityData> getAccessibility() { return accessibility; }
    public void setAccessibility(List<AccessibilityData> accessibility) { this.accessibility = accessibility; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
