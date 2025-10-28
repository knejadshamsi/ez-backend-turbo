package org.ez.mobility.api;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

public class SimulationRequest {
    @NotEmpty(message = "Zone links must not be empty")
    @Size(min = 1, message = "At least one zone link must be specified")
    private List<String> zoneLinks;

    private Map<String, Integer> lengthRestrictions;

    @Valid
    @NotEmpty(message = "At least one policy entry must be specified")
    private List<PolicyEntry> policy;

    @NotNull(message = "Operating start time must be specified")
    private LocalTime operatingStart;

    @NotNull(message = "Operating end time must be specified")
    private LocalTime operatingEnd;

    public static class PolicyEntry {
        @NotNull(message = "Policy ID must be specified")
        private String id;  // vehicle id or type

        @NotNull(message = "Policy mode must be specified")
        private String mode;  // "fixed", "hourly", "vehicle", or "banned"

        @Valid
        private PolicyOptions options;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public PolicyOptions getOptions() {
            return options;
        }

        public void setOptions(PolicyOptions options) {
            this.options = options;
        }
    }

    public static class PolicyOptions {
        private Double price;  // used for fixed and hourly
        private Double interval;  // used for hourly (0.5 to 23)
        private Double penalty;  // penalty for restricted vehicles
        private String mode;  // used for vehicle-specific mode

        public Double getPrice() {
            return price;
        }

        public void setPrice(Double price) {
            this.price = price;
        }

        public Double getInterval() {
            return interval;
        }

        public void setInterval(Double interval) {
            this.interval = interval;
        }

        public Double getPenalty() {
            return penalty;
        }

        public void setPenalty(Double penalty) {
            this.penalty = penalty;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    // Getters and setters
    public List<String> getZoneLinks() {
        return zoneLinks;
    }

    public void setZoneLinks(List<String> zoneLinks) {
        this.zoneLinks = zoneLinks;
    }

    public Map<String, Integer> getLengthRestrictions() {
        return lengthRestrictions;
    }

    public void setLengthRestrictions(Map<String, Integer> lengthRestrictions) {
        this.lengthRestrictions = lengthRestrictions;
    }

    public List<PolicyEntry> getPolicy() {
        return policy;
    }

    public void setPolicy(List<PolicyEntry> policy) {
        this.policy = policy;
    }

    public LocalTime getOperatingStart() {
        return operatingStart;
    }

    public void setOperatingStart(LocalTime operatingStart) {
        this.operatingStart = operatingStart;
    }

    public LocalTime getOperatingEnd() {
        return operatingEnd;
    }

    public void setOperatingEnd(LocalTime operatingEnd) {
        this.operatingEnd = operatingEnd;
    }
}
