package org.example;

import org.matsim.core.config.ReflectiveConfigGroup;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration group for Zero Emission Zone (ZEZ) simulation parameters.
 * 
 * This class defines and manages configuration settings specific to the Zero Emission Zone,
 * including zone boundaries, vehicle type restrictions, charging policies, and routing alternatives.
 * 
 * Configuration parameters cover:
 * - Zone link definitions
 * - Alternative routing options
 * - Vehicle type permissions
 * - Economic incentives and penalties
 * - Time-based charging mechanisms
 */
public class ZeroEmissionZoneConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "zeroEmissionZone";
    
    // ZEZ Network Configuration
    private String zoneLinks = "3,4";  // Links defining the Zero Emission Zone
    private String alternativeRoutes = "7,8,9,10";  // Bypass route links
    private double bypassRouteCapacityIncrease = 2.0;  // Capacity multiplier for bypass routes
    private double bypassRouteTravelTimeReduction = 0.7;  // Travel time reduction factor
    
    // Temporal Constraints
    private String startTime = "07:00:00";  // ZEZ operational start time
    private String endTime = "19:00:00";  // ZEZ operational end time
    
    // Vehicle Type Restrictions
    private String allowedVehicleTypes = "ev_car,lev_car";  // Permitted vehicle types
    
    // Charging and Economic Policies
    private boolean enableGraduatedCharging = true;  // Time-based charging mechanism
    private double baseCharge = 50.0;  // Base entry charge for ZEZ
    private double peakHourSurcharge = 100.0;  // Additional charge during peak hours
    
    // Scoring and Incentive Parameters
    private double evReward = 300.0;  // Bonus for electric vehicles
    private double levPenalty = -200.0;  // Penalty for low emission vehicles
    private double levAlternativeReward = 500.0;  // Reward for using alternative routes
    private double hevViolationPenalty = Double.NEGATIVE_INFINITY;  // Severe penalty for hybrid vehicles

    /**
     * Constructor initializes the configuration group with the ZEZ group name.
     */
    public ZeroEmissionZoneConfigGroup() {
        super(GROUP_NAME);
    }

    /**
     * Provides descriptive comments for each configuration parameter.
     * These comments help users understand the purpose and impact of each setting.
     * 
     * @return Map of parameter names to their descriptive comments
     */
    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = new HashMap<>();
        comments.put("zoneLinks", "Links in ZEZ (3,4)");
        comments.put("alternativeRoutes", "Bypass route links (7,8,9,10)");
        comments.put("bypassRouteCapacityIncrease", "Factor for bypass capacity (e.g., 2.0 doubles capacity)");
        comments.put("bypassRouteTravelTimeReduction", "Factor for bypass travel time (e.g., 0.7 reduces by 30%)");
        comments.put("startTime", "ZEZ start time (HH:mm:ss)");
        comments.put("endTime", "ZEZ end time (HH:mm:ss)");
        comments.put("allowedVehicleTypes", "Allowed vehicles (ev_car,lev_car)");
        comments.put("enableGraduatedCharging", "Enable time-based charging");
        comments.put("baseCharge", "Base ZEZ entry charge");
        comments.put("peakHourSurcharge", "Extra charge during peak hours");
        comments.put("evReward", "Score bonus for EVs");
        comments.put("levPenalty", "Score penalty for LEVs");
        comments.put("levAlternativeReward", "Score bonus for using bypass");
        comments.put("hevViolationPenalty", "Penalty for HEV violations");
        return comments;
    }

    @StringGetter("zoneLinks")
    public String getZoneLinks() { return zoneLinks; }

    @StringSetter("zoneLinks")
    public void setZoneLinks(String value) { this.zoneLinks = value; }

    @StringGetter("alternativeRoutes")
    public String getAlternativeRoutes() { return alternativeRoutes; }

    @StringSetter("alternativeRoutes")
    public void setAlternativeRoutes(String value) { this.alternativeRoutes = value; }

    @StringGetter("bypassRouteCapacityIncrease")
    public String getBypassRouteCapacityIncrease() { return String.valueOf(bypassRouteCapacityIncrease); }

    @StringSetter("bypassRouteCapacityIncrease")
    public void setBypassRouteCapacityIncrease(String value) { this.bypassRouteCapacityIncrease = Double.parseDouble(value); }

    @StringGetter("bypassRouteTravelTimeReduction")
    public String getBypassRouteTravelTimeReduction() { return String.valueOf(bypassRouteTravelTimeReduction); }

    @StringSetter("bypassRouteTravelTimeReduction")
    public void setBypassRouteTravelTimeReduction(String value) { this.bypassRouteTravelTimeReduction = Double.parseDouble(value); }

    @StringGetter("startTime")
    public String getStartTime() { return startTime; }

    @StringSetter("startTime")
    public void setStartTime(String value) { this.startTime = value; }

    @StringGetter("endTime")
    public String getEndTime() { return endTime; }

    @StringSetter("endTime")
    public void setEndTime(String value) { this.endTime = value; }

    @StringGetter("allowedVehicleTypes")
    public String getAllowedVehicleTypes() { return allowedVehicleTypes; }

    @StringSetter("allowedVehicleTypes")
    public void setAllowedVehicleTypes(String value) { this.allowedVehicleTypes = value; }

    @StringGetter("enableGraduatedCharging")
    public String getEnableGraduatedCharging() { return String.valueOf(enableGraduatedCharging); }

    @StringSetter("enableGraduatedCharging")
    public void setEnableGraduatedCharging(String value) { this.enableGraduatedCharging = Boolean.parseBoolean(value); }

    @StringGetter("baseCharge")
    public String getBaseCharge() { return String.valueOf(baseCharge); }

    @StringSetter("baseCharge")
    public void setBaseCharge(String value) { this.baseCharge = Double.parseDouble(value); }

    @StringGetter("peakHourSurcharge")
    public String getPeakHourSurcharge() { return String.valueOf(peakHourSurcharge); }

    @StringSetter("peakHourSurcharge")
    public void setPeakHourSurcharge(String value) { this.peakHourSurcharge = Double.parseDouble(value); }

    @StringGetter("evReward")
    public String getEvReward() { return String.valueOf(evReward); }

    @StringSetter("evReward")
    public void setEvReward(String value) { this.evReward = Double.parseDouble(value); }

    @StringGetter("levPenalty")
    public String getLevPenalty() { return String.valueOf(levPenalty); }

    @StringSetter("levPenalty")
    public void setLevPenalty(String value) { this.levPenalty = Double.parseDouble(value); }

    @StringGetter("levAlternativeReward")
    public String getLevAlternativeReward() { return String.valueOf(levAlternativeReward); }

    @StringSetter("levAlternativeReward")
    public void setLevAlternativeReward(String value) { this.levAlternativeReward = Double.parseDouble(value); }

    @StringGetter("hevViolationPenalty")
    public String getHevViolationPenalty() { return String.valueOf(hevViolationPenalty); }

    @StringSetter("hevViolationPenalty")
    public void setHevViolationPenalty(String value) { this.hevViolationPenalty = Double.parseDouble(value); }

    /**
     * Converts the allowed vehicle types string to an array.
     * 
     * @return Array of allowed vehicle type strings
     */
    public String[] getAllowedVehicleTypesArray() {
        return allowedVehicleTypes.split(",");
    }

    // Convenience methods to directly retrieve primitive values
    public double getEvRewardValue() { return evReward; }
    public double getLevPenaltyValue() { return levPenalty; }
    public double getLevAlternativeRewardValue() { return levAlternativeReward; }
    public double getHevViolationPenaltyValue() { return hevViolationPenalty; }
    public double getBypassRouteCapacityIncreaseValue() { return bypassRouteCapacityIncrease; }
    public double getBypassRouteTravelTimeReductionValue() { return bypassRouteTravelTimeReduction; }
    public boolean isEnableGraduatedChargingValue() { return enableGraduatedCharging; }
    public double getBaseChargeValue() { return baseCharge; }
    public double getPeakHourSurchargeValue() { return peakHourSurcharge; }
}
