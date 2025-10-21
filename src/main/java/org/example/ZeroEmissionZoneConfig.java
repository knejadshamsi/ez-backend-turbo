package org.example;

import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup.StringGetter;
import org.matsim.core.config.ReflectiveConfigGroup.StringSetter;

/**
 * Configuration class for Zero Emission Zone parameters.
 * Extends ReflectiveConfigGroup to integrate with MATSim's configuration system.
 */
public class ZeroEmissionZoneConfig extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "zeroEmissionZone";
    
    // Default values
    private String linkId = "2";
    private double penaltyScore = -100.0;
    private String startTime = "07:00:00";
    private String endTime = "19:00:00";
    private String outputDirectory = "./output/zez-analysis";
    private boolean generateViolationReport = true;
    private boolean generateStatsSummary = true;

    public ZeroEmissionZoneConfig() {
        super(GROUP_NAME);
    }

    @StringGetter("linkId")
    public String getLinkId() {
        return linkId;
    }

    @StringSetter("linkId")
    public void setLinkId(String linkId) {
        this.linkId = linkId;
    }

    @StringGetter("penaltyScore")
    public double getPenaltyScore() {
        return penaltyScore;
    }

    @StringSetter("penaltyScore")
    public void setPenaltyScore(double penaltyScore) {
        this.penaltyScore = penaltyScore;
    }

    @StringGetter("startTime")
    public String getStartTime() {
        return startTime;
    }

    @StringSetter("startTime")
    public void setStartTime(String time) {
        this.startTime = time;
    }

    @StringGetter("endTime")
    public String getEndTime() {
        return endTime;
    }

    @StringSetter("endTime")
    public void setEndTime(String time) {
        this.endTime = time;
    }

    @StringGetter("outputDirectory")
    public String getOutputDirectory() {
        return outputDirectory;
    }

    @StringSetter("outputDirectory")
    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @StringGetter("generateViolationReport")
    public boolean isGenerateViolationReport() {
        return generateViolationReport;
    }

    @StringSetter("generateViolationReport")
    public void setGenerateViolationReport(boolean generateViolationReport) {
        this.generateViolationReport = generateViolationReport;
    }

    @StringGetter("generateStatsSummary")
    public boolean isGenerateStatsSummary() {
        return generateStatsSummary;
    }

    @StringSetter("generateStatsSummary")
    public void setGenerateStatsSummary(boolean generateStatsSummary) {
        this.generateStatsSummary = generateStatsSummary;
    }
}