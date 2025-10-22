package org.example;

import org.matsim.core.config.ReflectiveConfigGroup;

public class ZeroEmissionZoneConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "zeroEmissionZone";
    
    private static final String LINK_ID = "linkId";
    private static final String PENALTY_SCORE = "penaltyScore";
    private static final String START_TIME = "startTime";
    private static final String END_TIME = "endTime";
    private static final String OUTPUT_DIRECTORY = "outputDirectory";
    private static final String GENERATE_VIOLATION_REPORT = "generateViolationReport";
    private static final String GENERATE_STATS_SUMMARY = "generateStatsSummary";
    private static final String MAX_VIOLATIONS = "maxViolationsBeforeSeverePenalty";
    
    private String linkId = "2";
    private double penaltyScore = -100.0;
    private String startTime = "07:00:00";
    private String endTime = "19:00:00";
    private String outputDirectory = "./output/zez-analysis";
    private boolean generateViolationReport = true;
    private boolean generateStatsSummary = true;
    private int maxViolationsBeforeSeverePenalty = 3;

    public ZeroEmissionZoneConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter(LINK_ID)
    public String getLinkId() {
        return linkId;
    }

    @StringSetter(LINK_ID)
    public void setLinkId(String linkId) {
        this.linkId = linkId;
    }

    @StringGetter(PENALTY_SCORE)
    public double getPenaltyScore() {
        return penaltyScore;
    }

    @StringSetter(PENALTY_SCORE)
    public void setPenaltyScore(double penaltyScore) {
        this.penaltyScore = penaltyScore;
    }

    @StringGetter(START_TIME)
    public String getStartTime() {
        return startTime;
    }

    @StringSetter(START_TIME)
    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    @StringGetter(END_TIME)
    public String getEndTime() {
        return endTime;
    }

    @StringSetter(END_TIME)
    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    @StringGetter(OUTPUT_DIRECTORY)
    public String getOutputDirectory() {
        return outputDirectory;
    }

    @StringSetter(OUTPUT_DIRECTORY)
    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    @StringGetter(GENERATE_VIOLATION_REPORT)
    public boolean isGenerateViolationReport() {
        return generateViolationReport;
    }

    @StringSetter(GENERATE_VIOLATION_REPORT)
    public void setGenerateViolationReport(boolean generateViolationReport) {
        this.generateViolationReport = generateViolationReport;
    }

    @StringGetter(GENERATE_STATS_SUMMARY)
    public boolean isGenerateStatsSummary() {
        return generateStatsSummary;
    }

    @StringSetter(GENERATE_STATS_SUMMARY)
    public void setGenerateStatsSummary(boolean generateStatsSummary) {
        this.generateStatsSummary = generateStatsSummary;
    }

    @StringGetter(MAX_VIOLATIONS)
    public int getMaxViolationsBeforeSeverePenalty() {
        return maxViolationsBeforeSeverePenalty;
    }

    @StringSetter(MAX_VIOLATIONS)
    public void setMaxViolationsBeforeSeverePenalty(int maxViolationsBeforeSeverePenalty) {
        this.maxViolationsBeforeSeverePenalty = maxViolationsBeforeSeverePenalty;
    }
}
