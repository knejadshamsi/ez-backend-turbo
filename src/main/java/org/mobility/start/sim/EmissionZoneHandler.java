package org.mobility.start.sim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.mobsim.qsim.QSim;
import org.springframework.stereotype.Component;
import java.util.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Component
public class EmissionZoneHandler implements LinkEnterEventHandler {
    private final Set<String> zoneLinks = new HashSet<>();
    private final Map<String, PolicyInfo> policies = new HashMap<>();
    private final Map<String, VehicleZoneInfo> vehicleZoneInfo = new HashMap<>();
    private final EmissionZonePenaltyCalculator penaltyCalculator;
    private final Tier2DecisionModule tier2DecisionModule;
    private final Tier3Replanner replanner;
    private QSim qsim;

    public EmissionZoneHandler(
        EmissionZonePenaltyCalculator penaltyCalculator, 
        Tier2DecisionModule tier2DecisionModule,
        Tier3Replanner replanner
    ) {
        this.penaltyCalculator = penaltyCalculator;
        this.tier2DecisionModule = tier2DecisionModule;
        this.replanner = replanner;
    }

    public void setQSim(QSim qsim) {
        this.qsim = qsim;
        this.replanner.setQSim(qsim);
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (qsim == null || !zoneLinks.contains(event.getLinkId().toString())) {
            return;
        }

        String vehicleId = event.getVehicleId().toString();
        int underscoreIndex = vehicleId.indexOf('_');
        if (underscoreIndex == -1) {
            return;
        }

        String vehicleType = vehicleId.substring(0, underscoreIndex);
        PolicyInfo policy = policies.get(vehicleType);
        
        if (policy == null || !isWithinOperatingHours(policy)) {
            return;
        }

        Id<Person> personId = Id.createPersonId(vehicleId.substring(underscoreIndex + 1));
        Person person = qsim.getScenario().getPopulation().getPersons().get(personId);
        
        if (person == null || person.getSelectedPlan() == null) {
            return;
        }

        if ("banned".equals(policy.policyValue)) {
            replanner.replanRoute(personId, zoneLinks.stream()
                .map(Id::createLinkId)
                .collect(Collectors.toList()));
            return;
        }

        if ("free".equals(policy.policyValue)) {
            return;
        }

        List<Double> values = parseValues(policy.policyValue);
        if (values.isEmpty()) {
            return;
        }

        VehicleZoneInfo zoneInfo = vehicleZoneInfo.computeIfAbsent(vehicleId, k -> new VehicleZoneInfo());
        double currentTime = qsim.getSimTimer().getTimeOfDay();

        if (zoneInfo.lastPenaltyTime < 0) {
            double penalty = values.get(0);
            double fine = values.size() > 1 ? values.get(1) : 0;
            penaltyCalculator.registerPenalty(vehicleId, penalty + fine);
            zoneInfo.lastPenaltyTime = currentTime;
        } else if (values.size() > 2) {
            double interval = values.get(2);
            double timeSinceLastPenalty = currentTime - zoneInfo.lastPenaltyTime;
            if (timeSinceLastPenalty >= interval) {
                double penalty = values.get(0);
                double fine = values.size() > 1 ? values.get(1) : 0;
                penaltyCalculator.registerPenalty(vehicleId, penalty + fine);
                zoneInfo.lastPenaltyTime = currentTime;
            }
        }

        if (!tier2DecisionModule.shouldEnterZone(person, person.getSelectedPlan(), 
            new ArrayList<>(zoneLinks), values.get(0))) {
            replanner.replanRoute(personId, zoneLinks.stream()
                .map(Id::createLinkId)
                .collect(Collectors.toList()));
        }
    }

    private List<Double> parseValues(String policyValue) {
        if (policyValue == null || policyValue.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return Arrays.stream(policyValue.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(Double::parseDouble)
            .collect(Collectors.toList());
    }

    private boolean isWithinOperatingHours(PolicyInfo policy) {
        LocalTime simTime = LocalTime.ofSecondOfDay((long) qsim.getSimTimer().getTimeOfDay());
        if (policy.startTime.equals(policy.endTime)) {
            return true;
        }
        return policy.startTime.isBefore(policy.endTime) ?
            !simTime.isBefore(policy.startTime) && simTime.isBefore(policy.endTime) :
            !simTime.isBefore(policy.startTime) || simTime.isBefore(policy.endTime);
    }

    public void addZoneLink(String linkId) {
        zoneLinks.add(linkId);
    }

    public void addPolicy(String vehicleType, String policyValue, String[] operatingHours) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        policies.put(vehicleType, new PolicyInfo(
            policyValue,
            LocalTime.parse(operatingHours[0], formatter),
            LocalTime.parse(operatingHours[1], formatter)
        ));
    }

    @Override
    public void reset(int iteration) {
        policies.clear();
        zoneLinks.clear();
        vehicleZoneInfo.clear();
    }

    private static class PolicyInfo {
        final String policyValue;
        final LocalTime startTime;
        final LocalTime endTime;

        PolicyInfo(String policyValue, LocalTime startTime, LocalTime endTime) {
            this.policyValue = policyValue;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    private static class VehicleZoneInfo {
        double lastPenaltyTime = -1;
    }
}
