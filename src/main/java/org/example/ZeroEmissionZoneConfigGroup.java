package org.example;

import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup.StringGetter;
import org.matsim.core.config.ReflectiveConfigGroup.StringSetter;

import java.util.HashMap;
import java.util.Map;

public class ZeroEmissionZoneConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "zeroEmissionZone";
    
    // Zone configuration parameters
    private double zoneRadius = 1000.0;
    private String zoneCenterCoordinates = "0,0";
    private boolean enablePenalties = true;
    private double penaltyRate = 5.0;
    private String exemptVehicleTypes = "electric";
    
    // Zone definition
    private String zoneLinks = "";
    private String alternativeRoutes = "";
    
    // Category-specific scores with adjusted defaults
    private double evReward = 75.0;  // Increased reward for EVs
    private double levPenalty = -150.0;  // Increased penalty for LEVs
    private double levAlternativeReward = 50.0;  // Increased reward for alternative routes
    private double hevViolationPenalty = -1000.0;  // Severe penalty for HEV violations
    
    // Time settings
    private String startTime = "07:00:00";  // Changed to match new operating hours
    private String endTime = "19:00:00";    // Changed to match new operating hours
    
    // Peak hour settings
    private String morningPeakStart = "07:00:00";
    private String morningPeakEnd = "09:00:00";
    private String eveningPeakStart = "16:00:00";
    private String eveningPeakEnd = "18:00:00";
    private double peakHourMultiplier = 1.5;
    private double offPeakMultiplier = 0.5;
    
    // Output configuration
    private String outputDirectory = "output/zero-emission-zone";
    private boolean generateReports = true;

    public ZeroEmissionZoneConfigGroup() {
        super(GROUP_NAME);
    }

    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = super.getComments();
        comments.put("zoneRadius", "Radius of the zero emission zone in meters");
        comments.put("zoneCenterCoordinates", "Center coordinates of the zone (x,y)");
        comments.put("enablePenalties", "Enable penalties for non-compliant vehicles");
        comments.put("penaltyRate", "Penalty rate per kilometer for non-compliant vehicles");
        comments.put("exemptVehicleTypes", "Comma-separated list of exempt vehicle types");
        comments.put("zoneLinks", "Comma-separated list of link IDs in the zero emission zone");
        comments.put("alternativeRoutes", "Comma-separated list of alternative route link IDs");
        comments.put("startTime", "Start time of zone operation (HH:mm:ss)");
        comments.put("endTime", "End time of zone operation (HH:mm:ss)");
        comments.put("morningPeakStart", "Start time of morning peak hours (HH:mm:ss)");
        comments.put("morningPeakEnd", "End time of morning peak hours (HH:mm:ss)");
        comments.put("eveningPeakStart", "Start time of evening peak hours (HH:mm:ss)");
        comments.put("eveningPeakEnd", "End time of evening peak hours (HH:mm:ss)");
        comments.put("peakHourMultiplier", "Score multiplier during peak hours");
        comments.put("offPeakMultiplier", "Score multiplier during off-peak hours");
        comments.put("evReward", "Score reward for electric vehicles using the zone");
        comments.put("levPenalty", "Score penalty for low emission vehicles in the zone");
        comments.put("levAlternativeReward", "Score reward for using alternative routes");
        comments.put("hevViolationPenalty", "Score penalty for heavy emission vehicles violating zone rules");
        comments.put("outputDirectory", "Output directory for reports");
        comments.put("generateReports", "Generate detailed reports");
        return comments;
    }

    // Existing getters and setters

    @StringGetter("zoneRadius")
    public double getZoneRadius() {
        return zoneRadius;
    }

    @StringSetter("zoneRadius")
    public void setZoneRadius(String value) {
        this.zoneRadius = Double.parseDouble(value);
    }

    @StringGetter("zoneCenterCoordinates")
    public String getZoneCenterCoordinates() {
        return zoneCenterCoordinates;
    }

    @StringSetter("zoneCenterCoordinates")
    public void setZoneCenterCoordinates(String value) {
        this.zoneCenterCoordinates = value;
    }

    @StringGetter("enablePenalties")
    public String getEnablePenalties() {
        return Boolean.toString(enablePenalties);
    }

    @StringSetter("enablePenalties")
    public void setEnablePenalties(String value) {
        this.enablePenalties = Boolean.parseBoolean(value);
    }

    @StringGetter("penaltyRate")
    public String getPenaltyRate() {
        return Double.toString(penaltyRate);
    }

    @StringSetter("penaltyRate")
    public void setPenaltyRate(String value) {
        this.penaltyRate = Double.parseDouble(value);
    }

    @StringGetter("exemptVehicleTypes")
    public String getExemptVehicleTypes() {
        return exemptVehicleTypes;
    }

    @StringSetter("exemptVehicleTypes")
    public void setExemptVehicleTypes(String value) {
        this.exemptVehicleTypes = value;
    }

    @StringGetter("startTime")
    public String getStartTime() {
        return startTime;
    }

    @StringSetter("startTime")
    public void setStartTime(String value) {
        this.startTime = value;
    }

    @StringGetter("endTime")
    public String getEndTime() {
        return endTime;
    }

    @StringSetter("endTime")
    public void setEndTime(String value) {
        this.endTime = value;
    }

    // New peak hour getters and setters
    @StringGetter("morningPeakStart")
    public String getMorningPeakStart() {
        return morningPeakStart;
    }

    @StringSetter("morningPeakStart")
    public void setMorningPeakStart(String value) {
        this.morningPeakStart = value;
    }

    @StringGetter("morningPeakEnd")
    public String getMorningPeakEnd() {
        return morningPeakEnd;
    }

    @StringSetter("morningPeakEnd")
    public void setMorningPeakEnd(String value) {
        this.morningPeakEnd = value;
    }

    @StringGetter("eveningPeakStart")
    public String getEveningPeakStart() {
        return eveningPeakStart;
    }

    @StringSetter("eveningPeakStart")
    public void setEveningPeakStart(String value) {
        this.eveningPeakStart = value;
    }

    @StringGetter("eveningPeakEnd")
    public String getEveningPeakEnd() {
        return eveningPeakEnd;
    }

    @StringSetter("eveningPeakEnd")
    public void setEveningPeakEnd(String value) {
        this.eveningPeakEnd = value;
    }

    @StringGetter("peakHourMultiplier")
    public String getPeakHourMultiplier() {
        return Double.toString(peakHourMultiplier);
    }

    @StringSetter("peakHourMultiplier")
    public void setPeakHourMultiplier(String value) {
        this.peakHourMultiplier = Double.parseDouble(value);
    }

    @StringGetter("offPeakMultiplier")
    public String getOffPeakMultiplier() {
        return Double.toString(offPeakMultiplier);
    }

    @StringSetter("offPeakMultiplier")
    public void setOffPeakMultiplier(String value) {
        this.offPeakMultiplier = Double.parseDouble(value);
    }

    // Zone links getters and setters
    @StringGetter("zoneLinks")
    public String getZoneLinks() {
        return zoneLinks;
    }

    @StringSetter("zoneLinks")
    public void setZoneLinks(String value) {
        this.zoneLinks = value;
    }

    @StringGetter("alternativeRoutes")
    public String getAlternativeRoutes() {
        return alternativeRoutes;
    }

    @StringSetter("alternativeRoutes")
    public void setAlternativeRoutes(String value) {
        this.alternativeRoutes = value;
    }

    // Score getters and setters
    @StringGetter("evReward")
    public String getEvReward() {
        return Double.toString(evReward);
    }

    @StringSetter("evReward")
    public void setEvReward(String value) {
        this.evReward = Double.parseDouble(value);
    }

    @StringGetter("levPenalty")
    public String getLevPenalty() {
        return Double.toString(levPenalty);
    }

    @StringSetter("levPenalty")
    public void setLevPenalty(String value) {
        this.levPenalty = Double.parseDouble(value);
    }

    @StringGetter("levAlternativeReward")
    public String getLevAlternativeReward() {
        return Double.toString(levAlternativeReward);
    }

    @StringSetter("levAlternativeReward")
    public void setLevAlternativeReward(String value) {
        this.levAlternativeReward = Double.parseDouble(value);
    }

    @StringGetter("hevViolationPenalty")
    public String getHevViolationPenalty() {
        return Double.toString(hevViolationPenalty);
    }

    @StringSetter("hevViolationPenalty")
    public void setHevViolationPenalty(String value) {
        this.hevViolationPenalty = Double.parseDouble(value);
    }

    @StringGetter("outputDirectory")
    public String getOutputDirectory() {
        return outputDirectory;
    }

    @StringSetter("outputDirectory")
    public void setOutputDirectory(String value) {
        this.outputDirectory = value;
    }

    @StringGetter("generateReports")
    public String getGenerateReports() {
        return Boolean.toString(generateReports);
    }

    @StringSetter("generateReports")
    public void setGenerateReports(String value) {
        this.generateReports = Boolean.parseBoolean(value);
    }

    // Utility methods
    public Map<String, String> getZoneLinksMap() {
        Map<String, String> map = new HashMap<>();
        if (zoneLinks != null && !zoneLinks.isEmpty()) {
            String[] links = zoneLinks.split(",");
            for (String link : links) {
                map.put(link.trim(), "ZONE");
            }
        }
        return map;
    }

    public Map<String, String> getAlternativeRoutesMap() {
        Map<String, String> map = new HashMap<>();
        if (alternativeRoutes != null && !alternativeRoutes.isEmpty()) {
            String[] routes = alternativeRoutes.split(",");
            for (String route : routes) {
                map.put(route.trim(), "ALTERNATIVE");
            }
        }
        return map;
    }

    // Convenience methods for type-safe access
    public boolean isEnablePenaltiesValue() {
        return enablePenalties;
    }

    public double getZoneRadiusValue() {
        return zoneRadius;
    }

    public double getPenaltyRateValue() {
        return penaltyRate;
    }

    public double getEvRewardValue() {
        return evReward;
    }

    public double getLevPenaltyValue() {
        return levPenalty;
    }

    public double getLevAlternativeRewardValue() {
        return levAlternativeReward;
    }

    public double getHevViolationPenaltyValue() {
        return hevViolationPenalty;
    }

    public double getPeakHourMultiplierValue() {
        return peakHourMultiplier;
    }

    public double getOffPeakMultiplierValue() {
        return offPeakMultiplier;
    }

    public boolean isGenerateReportsValue() {
        return generateReports;
    }

    public String[] getExemptVehicleTypesArray() {
        return exemptVehicleTypes.split(",");
    }
}
