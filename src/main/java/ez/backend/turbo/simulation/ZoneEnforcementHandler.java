package ez.backend.turbo.simulation;

import com.google.inject.Inject;
import ez.backend.turbo.simulation.ZonePolicyIndex.EnforcementRule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ZoneEnforcementHandler implements LinkEnterEventHandler, VehicleEntersTrafficEventHandler {

    private static final double BAN_PENALTY = -10_000.0;

    private final ZonePolicyIndex index;
    private final EventsManager eventsManager;
    private final Vehicles vehicles;
    private final ConcurrentHashMap<Id<Vehicle>, Id<Person>> vehiclePersonMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id<Vehicle>, ConcurrentHashMap<String, EntryRecord>> entryTimestamps = new ConcurrentHashMap<>();

    private record EntryRecord(double time, EnforcementRule rule) {}

    @Inject
    ZoneEnforcementHandler(ZonePolicyIndex index, EventsManager eventsManager, Scenario scenario) {
        this.index = index;
        this.eventsManager = eventsManager;
        this.vehicles = scenario.getVehicles();
    }

    @Override
    public void reset(int iteration) {
        vehiclePersonMap.clear();
        entryTimestamps.clear();
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        vehiclePersonMap.put(event.getVehicleId(), event.getPersonId());

        Id<Vehicle> vehicleId = event.getVehicleId();
        Vehicle vehicle = vehicles.getVehicles().get(vehicleId);
        if (vehicle == null) return;
        String vehicleType = vehicle.getType().getId().toString();

        Id<Link> linkId = event.getLinkId();
        double time = event.getTime();
        checkEntryGateway(linkId, vehicleId, vehicleType, time);
        checkExitGateway(linkId, vehicleId, time);
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        Id<Link> linkId = event.getLinkId();
        Id<Vehicle> vehicleId = event.getVehicleId();
        double time = event.getTime();

        Vehicle vehicle = vehicles.getVehicles().get(vehicleId);
        if (vehicle == null) return;
        String vehicleType = vehicle.getType().getId().toString();

        checkEntryGateway(linkId, vehicleId, vehicleType, time);
        checkExitGateway(linkId, vehicleId, time);
    }

    private void checkEntryGateway(Id<Link> linkId, Id<Vehicle> vehicleId,
                                   String vehicleType, double time) {
        List<EnforcementRule> rules = index.getEntryRules(linkId);
        if (rules == null) return;

        for (EnforcementRule rule : rules) {
            if (!rule.vehicleType().equals(vehicleType)) continue;
            if (time < rule.startTime() || time >= rule.endTime()) continue;

            if (rule.tier() == 3) {
                Id<Person> personId = vehiclePersonMap.get(vehicleId);
                if (personId != null) {
                    eventsManager.processEvent(
                            new PersonMoneyEvent(time, personId, BAN_PENALTY, "zone_ban", rule.zoneId(), null));
                }
            } else if (rule.tier() == 2) {
                entryTimestamps
                        .computeIfAbsent(vehicleId, k -> new ConcurrentHashMap<>())
                        .put(rule.zoneId(), new EntryRecord(time, rule));
            }
        }
    }

    private void checkExitGateway(Id<Link> linkId, Id<Vehicle> vehicleId, double time) {
        Set<String> zones = index.getExitZones(linkId);
        if (zones == null) return;

        ConcurrentHashMap<String, EntryRecord> entries = entryTimestamps.get(vehicleId);
        if (entries == null) return;

        for (String zoneId : zones) {
            EntryRecord record = entries.remove(zoneId);
            if (record == null) continue;

            double duration = time - record.time();
            int intervals = (int) Math.floor(duration / record.rule().intervalSeconds());
            if (intervals <= 0) continue;

            double charge = intervals * record.rule().penalty();
            Id<Person> personId = vehiclePersonMap.get(vehicleId);
            if (personId != null) {
                eventsManager.processEvent(
                        new PersonMoneyEvent(time, personId, -charge, "zone_penalty", zoneId, null));
            }
        }
    }
}
