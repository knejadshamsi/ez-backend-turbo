package ez.backend.turbo.endpoints;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class SimulationRequest {

    @JsonProperty("zones")
    private List<Zone> zones;

    @JsonProperty("customSimulationAreas")
    private List<CustomSimulationArea> customSimulationAreas;

    @JsonProperty("scaledSimulationAreas")
    private List<ScaledSimulationArea> scaledSimulationAreas;

    @JsonProperty("sources")
    private Sources sources;

    @JsonProperty("simulationOptions")
    private SimulationOptions simulationOptions;

    @JsonProperty("carDistribution")
    private CarDistribution carDistribution;

    @JsonProperty("modeUtilities")
    private ModeUtilities modeUtilities;

    public List<Zone> getZones() { return zones; }
    public List<CustomSimulationArea> getCustomSimulationAreas() { return customSimulationAreas; }
    public List<ScaledSimulationArea> getScaledSimulationAreas() { return scaledSimulationAreas; }
    public Sources getSources() { return sources; }
    public SimulationOptions getSimulationOptions() { return simulationOptions; }
    public CarDistribution getCarDistribution() { return carDistribution; }
    public ModeUtilities getModeUtilities() { return modeUtilities; }

    public static class Zone {

        @JsonProperty("id")
        private String id;

        @JsonProperty("coords")
        private List<List<double[]>> coords;

        @JsonProperty("trip")
        private List<String> trip;

        @JsonProperty("policies")
        private List<Policy> policies;

        public String getId() { return id; }
        public List<List<double[]>> getCoords() { return coords; }
        public List<String> getTrip() { return trip; }
        public List<Policy> getPolicies() { return policies; }
    }

    public static class CustomSimulationArea {

        @JsonProperty("id")
        private String id;

        @JsonProperty("coords")
        private List<List<double[]>> coords;

        public String getId() { return id; }
        public List<List<double[]>> getCoords() { return coords; }
    }

    public static class ScaledSimulationArea {

        @JsonProperty("id")
        private String id;

        @JsonProperty("zoneId")
        private String zoneId;

        @JsonProperty("coords")
        private List<List<double[]>> coords;

        public String getId() { return id; }
        public String getZoneId() { return zoneId; }
        public List<List<double[]>> getCoords() { return coords; }
    }

    public static class Policy {

        @JsonProperty("vehicleType")
        private String vehicleType;

        @JsonProperty("tier")
        private int tier;

        @JsonProperty("period")
        private List<String> period;

        @JsonProperty("penalty")
        private Double penalty;

        @JsonProperty("interval")
        private Integer interval;

        public String getVehicleType() { return vehicleType; }
        public int getTier() { return tier; }
        public List<String> getPeriod() { return period; }
        public Double getPenalty() { return penalty; }
        public Integer getInterval() { return interval; }
    }

    public static class Sources {

        @JsonProperty("population")
        private DataSource population;

        @JsonProperty("network")
        private DataSource network;

        @JsonProperty("publicTransport")
        private DataSource publicTransport;

        public DataSource getPopulation() { return population; }
        public DataSource getNetwork() { return network; }
        public DataSource getPublicTransport() { return publicTransport; }
    }

    public static class DataSource {

        @JsonProperty("year")
        private int year;

        @JsonProperty("name")
        private String name;

        public int getYear() { return year; }
        public String getName() { return name; }
    }

    public static class SimulationOptions {

        @JsonProperty("iterations")
        private int iterations;

        @JsonProperty("percentage")
        private int percentage;

        public int getIterations() { return iterations; }
        public int getPercentage() { return percentage; }
    }

    public static class CarDistribution {

        @JsonProperty("zeroEmission")
        private double zeroEmission;

        @JsonProperty("nearZeroEmission")
        private double nearZeroEmission;

        @JsonProperty("lowEmission")
        private double lowEmission;

        @JsonProperty("midEmission")
        private double midEmission;

        @JsonProperty("highEmission")
        private double highEmission;

        public double getZeroEmission() { return zeroEmission; }
        public double getNearZeroEmission() { return nearZeroEmission; }
        public double getLowEmission() { return lowEmission; }
        public double getMidEmission() { return midEmission; }
        public double getHighEmission() { return highEmission; }
    }

    public static class ModeUtilities {

        @JsonProperty("walk")
        private double walk;

        @JsonProperty("bike")
        private double bike;

        @JsonProperty("car")
        private double car;

        @JsonProperty("ev")
        private double ev;

        @JsonProperty("subway")
        private double subway;

        @JsonProperty("bus")
        private double bus;

        public double getWalk() { return walk; }
        public double getBike() { return bike; }
        public double getCar() { return car; }
        public double getEv() { return ev; }
        public double getSubway() { return subway; }
        public double getBus() { return bus; }
    }
}
