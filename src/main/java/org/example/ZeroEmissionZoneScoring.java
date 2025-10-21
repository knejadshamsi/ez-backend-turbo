package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.vehicles.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ZeroEmissionZoneScoring implements ScoringFunctionFactory, LinkEnterEventHandler, PersonEntersVehicleEventHandler {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZoneScoring.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_TIME;
    private static final double STUCK_PENALTY = -1000.0;

    private final Scenario scenario;
    private final StatisticsCollector statsCollector;
    private final ZeroEmissionZoneConfig zezConfig;
    private final Map<Id<Person>, Integer> violations;

    public ZeroEmissionZoneScoring(Scenario scenario, StatisticsCollector statsCollector) {
        this.scenario = scenario;
        this.statsCollector = statsCollector;
        this.zezConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), ZeroEmissionZoneConfig.class);
        this.violations = new ConcurrentHashMap<>();
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (isWithinOperatingHours(event.getTime()) && event.getLinkId().toString().equals(zezConfig.getLinkId())) {
            processVehicleEntry(event);
        }
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        Vehicle vehicle = scenario.getVehicles().getVehicles().get(event.getVehicleId());
        if (vehicle != null) {
            vehicle.getAttributes().putAttribute("driverId", event.getPersonId().toString());
        }
    }

    private boolean isWithinOperatingHours(double time) {
        LocalTime eventTime = LocalTime.ofSecondOfDay((long) time);
        LocalTime startTime = LocalTime.parse(zezConfig.getStartTime(), TIME_FORMATTER);
        LocalTime endTime = LocalTime.parse(zezConfig.getEndTime(), TIME_FORMATTER);
        return !eventTime.isBefore(startTime) && !eventTime.isAfter(endTime);
    }

    private void processVehicleEntry(LinkEnterEvent event) {
        Vehicle vehicle = scenario.getVehicles().getVehicles().get(event.getVehicleId());
        if (vehicle == null) {
            logger.warn("Vehicle {} not found in scenario", event.getVehicleId());
            return;
        }
    
        String driverIdString = (String) vehicle.getAttributes().getAttribute("driverId");
        if (driverIdString == null) {
            logger.warn("No driver ID found for vehicle {}", event.getVehicleId());
            return;
        }
    
        Id<Person> driverId = Id.createPersonId(driverIdString);
        boolean isZeroEmission = isZeroEmissionVehicle(vehicle);
        
        logger.info("Vehicle {} of type {} entered ZEZ at time {}. Is zero emission: {}", 
            event.getVehicleId(), vehicle.getType().getId(), event.getTime(), isZeroEmission);
        
        if (!isZeroEmission) {
            violations.merge(driverId, 1, Integer::sum);
            logger.info("Recorded violation for driver {} in vehicle {}", driverId, event.getVehicleId());
        }
        statsCollector.recordEntry(event, isZeroEmission);
    }

    private boolean isZeroEmissionVehicle(Vehicle vehicle) {
        String typeId = vehicle.getType().getId().toString();
        return typeId.equals("zev");
    }

    @Override
    public void reset(int iteration) {
        if (!violations.isEmpty()) {
            statsCollector.saveIterationStats(iteration, violations);
        }
        violations.clear();
    }

    @Override
    public ScoringFunction createNewScoringFunction(Person person) {
        return new ZeroEmissionZoneScoringFunction(person, zezConfig, violations.getOrDefault(person.getId(), 0));
    }

    private static class ZeroEmissionZoneScoringFunction implements ScoringFunction {
        private final double violationPenalty;
        private final int violations;
        private double score;
        private final Person person;

        public ZeroEmissionZoneScoringFunction(Person person, ZeroEmissionZoneConfig config, int violations) {
            this.person = person;
            this.violations = violations;
            this.violationPenalty = config.getPenaltyScore();
            this.score = 0.0;
        }

        @Override
        public void finish() {
            score += violations * violationPenalty;
        }

        @Override
        public double getScore() {
            return score;
        }

        @Override
        public void addScore(double increment) {
            score += increment;
        }

        @Override
        public void addMoney(double amount) {
            score += amount;
        }

        @Override
        public void agentStuck(double time) {
            score += STUCK_PENALTY;
        }

        @Override
        public void handleEvent(org.matsim.api.core.v01.events.Event event) {}
        @Override
        public void handleActivity(org.matsim.api.core.v01.population.Activity act) {}
        @Override
        public void handleLeg(org.matsim.api.core.v01.population.Leg leg) {}
    }
}
