package org.ez.mobility.ez;

import org.matsim.core.config.ReflectiveConfigGroup;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZeroEmissionZoneConfigGroup extends ReflectiveConfigGroup {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZoneConfigGroup.class);
    public static final String GROUP_NAME = "zeroEmissionZone";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private String zoneLinkIds = "";
    private String allowedVehicleTypes = "";
    private String policyConfiguration = "";
    private String enabled = "false";
    private String operatingStartTimeStr = "07:00";
    private String operatingEndTimeStr = "19:00";
    private String lengthRestrictionsStr = "";

    public ZeroEmissionZoneConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter("zoneLinkIds")
    public String getZoneLinkIds() {
        return zoneLinkIds;
    }

    @StringSetter("zoneLinkIds")
    public void setZoneLinkIds(String zoneLinkIds) {
        this.zoneLinkIds = zoneLinkIds != null ? zoneLinkIds : "";
    }

    @StringGetter("allowedVehicleTypes")
    public String getAllowedVehicleTypes() {
        return allowedVehicleTypes;
    }

    @StringSetter("allowedVehicleTypes")
    public void setAllowedVehicleTypes(String allowedVehicleTypes) {
        this.allowedVehicleTypes = allowedVehicleTypes != null ? allowedVehicleTypes : "";
    }

    @StringGetter("policyConfiguration")
    public String getPolicyConfiguration() {
        return policyConfiguration;
    }

    @StringSetter("policyConfiguration")
    public void setPolicyConfiguration(String policyConfiguration) {
        this.policyConfiguration = policyConfiguration != null ? policyConfiguration : "";
    }

    @StringGetter("enabled")
    public String getEnabled() {
        return enabled;
    }

    @StringSetter("enabled")
    public void setEnabled(String enabled) {
        this.enabled = enabled != null ? enabled : "false";
    }

    @StringGetter("operatingStartTime")
    public String getOperatingStartTimeString() {
        return operatingStartTimeStr;
    }

    @StringSetter("operatingStartTime")
    public void setOperatingStartTimeString(String time) {
        try {
            LocalTime.parse(time, TIME_FORMATTER); // Validate format
            this.operatingStartTimeStr = time;
        } catch (DateTimeParseException e) {
            logger.error("Invalid start time format: {}. Using default 07:00", time);
            this.operatingStartTimeStr = "07:00";
        }
    }

    @StringGetter("operatingEndTime")
    public String getOperatingEndTimeString() {
        return operatingEndTimeStr;
    }

    @StringSetter("operatingEndTime")
    public void setOperatingEndTimeString(String time) {
        try {
            LocalTime.parse(time, TIME_FORMATTER); // Validate format
            this.operatingEndTimeStr = time;
        } catch (DateTimeParseException e) {
            logger.error("Invalid end time format: {}. Using default 19:00", time);
            this.operatingEndTimeStr = "19:00";
        }
    }

    public LocalTime getOperatingStartTime() {
        try {
            return LocalTime.parse(operatingStartTimeStr, TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return LocalTime.of(7, 0);
        }
    }

    public void setOperatingStartTime(LocalTime time) {
        this.operatingStartTimeStr = time.format(TIME_FORMATTER);
    }

    public LocalTime getOperatingEndTime() {
        try {
            return LocalTime.parse(operatingEndTimeStr, TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return LocalTime.of(19, 0);
        }
    }

    public void setOperatingEndTime(LocalTime time) {
        this.operatingEndTimeStr = time.format(TIME_FORMATTER);
    }

    @StringGetter("lengthRestrictions")
    public String getLengthRestrictionsString() {
        return lengthRestrictionsStr;
    }

    @StringSetter("lengthRestrictions")
    public void setLengthRestrictionsString(String restrictions) {
        this.lengthRestrictionsStr = restrictions != null ? restrictions : "";
        parseLengthRestrictions(this.lengthRestrictionsStr);
    }

    private Map<String, Integer> lengthRestrictions = new HashMap<>();

    public Map<String, Integer> getLengthRestrictions() {
        return new HashMap<>(lengthRestrictions);
    }

    public void setLengthRestrictions(Map<String, Integer> restrictions) {
        this.lengthRestrictions = new HashMap<>(restrictions);
        this.lengthRestrictionsStr = serializeLengthRestrictions();
    }

    private String serializeLengthRestrictions() {
        return lengthRestrictions.entrySet().stream()
            .map(e -> e.getKey() + "_" + e.getValue())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    }

    private void parseLengthRestrictions(String restrictionsStr) {
        lengthRestrictions.clear();
        if (restrictionsStr == null || restrictionsStr.isEmpty()) {
            return;
        }

        for (String restriction : restrictionsStr.split(",")) {
            String[] parts = restriction.split("_");
            if (parts.length == 2) {
                try {
                    lengthRestrictions.put(parts[0], Integer.parseInt(parts[1]));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid length restriction format: {}", restriction);
                }
            }
        }
    }
}
