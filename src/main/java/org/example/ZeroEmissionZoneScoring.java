package org.example;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.CharyparNagelAgentStuckScoring;
import org.matsim.core.scoring.functions.CharyparNagelLegScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.util.HashSet;
import java.util.Set;

public class ZeroEmissionZoneScoring implements ScoringFunctionFactory, LinkEnterEventHandler {
    private final ScoringParametersForPerson params;
    private final Set<Id<Person>> violators;
    private final String zezLinkId;
    private final double PENALTY = -100.0; // Increased penalty
    private final Scenario scenario;

    @Inject
    public ZeroEmissionZoneScoring(
            ScoringParametersForPerson params,
            @Named("zezLinkId") String zezLinkId,
            Scenario scenario
    ) {
        this.params = params;
        this.zezLinkId = zezLinkId;
        this.violators = new HashSet<>();
        this.scenario = scenario;
        System.out.println("ZEZ Scoring initialized with link ID: " + zezLinkId);
        
        // Log vehicle types at initialization
        scenario.getVehicles().getVehicleTypes().forEach((id, type) -> {
            System.out.println("Vehicle type: " + id);
            System.out.println("  Attributes: " + type.getAttributes().getAsMap());
        });
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (event.getLinkId().toString().equals(zezLinkId)) {
            Vehicle vehicle = scenario.getVehicles().getVehicles().get(event.getVehicleId());
            if (vehicle != null) {
                VehicleType vehicleType = vehicle.getType();
                Boolean isZeroEmission = (Boolean) vehicleType.getAttributes().getAttribute("isZeroEmission");
                
                System.out.println("Vehicle " + event.getVehicleId() + " (type: " + vehicleType.getId() + ") entered ZEZ");
                System.out.println("  isZeroEmission attribute: " + isZeroEmission);
                
                if (isZeroEmission == null || !isZeroEmission) {
                    Id<Person> personId = Id.createPersonId(event.getVehicleId().toString().replace("_car", ""));
                    violators.add(personId);
                    System.out.println("Added penalty score (" + PENALTY + ") for vehicle " + event.getVehicleId() + 
                        " entering zero-emission zone at time " + event.getTime());
                } else {
                    System.out.println("Zero-emission vehicle " + event.getVehicleId() + " allowed in zone");
                }
            } else {
                System.out.println("WARNING: Vehicle not found in scenario: " + event.getVehicleId());
            }
        }
    }

    @Override
    public void reset(int iteration) {
        System.out.println("Resetting violators list for iteration " + iteration);
        System.out.println("Previous iteration had " + violators.size() + " violators");
        violators.clear();
    }
}