package ez.backend.turbo.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.springframework.stereotype.Component;

import ez.backend.turbo.utils.L;

@Component
public class TransitPreparer {

    private static final Logger log = LogManager.getLogger(TransitPreparer.class);

    public void prepare(TransitSchedule schedule, Vehicles vehicles, Network network) {
        prefixTransitVehicleIds(schedule, vehicles);
        tagBusVehicleTypes(vehicles);
        cleanSchedule(schedule, network);
        validateSchedule(schedule, network);
    }

    private void prefixTransitVehicleIds(TransitSchedule schedule, Vehicles transitVehicles) {
        Set<String> busRouteVehicleIds = new HashSet<>();
        Set<String> subwayRouteVehicleIds = new HashSet<>();

        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                String mode = route.getTransportMode();
                for (var departure : route.getDepartures().values()) {
                    String vid = departure.getVehicleId().toString();
                    if ("subway".equals(mode) || "rail".equals(mode) || "metro".equals(mode)) {
                        subwayRouteVehicleIds.add(vid);
                    } else {
                        busRouteVehicleIds.add(vid);
                    }
                }
            }
        }

        Map<Id<Vehicle>, String> renames = new HashMap<>();
        for (Vehicle v : transitVehicles.getVehicles().values()) {
            String id = v.getId().toString();
            if (subwayRouteVehicleIds.contains(id)) {
                renames.put(v.getId(), "subway_" + id);
            } else if (busRouteVehicleIds.contains(id)) {
                renames.put(v.getId(), "bus_" + id);
            }
        }

        for (var entry : renames.entrySet()) {
            Vehicle old = transitVehicles.getVehicles().get(entry.getKey());
            transitVehicles.removeVehicle(entry.getKey());
            Vehicle renamed = transitVehicles.getFactory()
                    .createVehicle(Id.createVehicleId(entry.getValue()), old.getType());
            transitVehicles.addVehicle(renamed);
        }

        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (var departure : route.getDepartures().values()) {
                    String oldId = departure.getVehicleId().toString();
                    String newId = renames.get(Id.createVehicleId(oldId));
                    if (newId != null) {
                        departure.setVehicleId(Id.createVehicleId(newId));
                    }
                }
            }
        }
    }

    private void tagBusVehicleTypes(Vehicles transitVehicles) {
        for (VehicleType vt : transitVehicles.getVehicleTypes().values()) {
            EngineInformation ei = vt.getEngineInformation();
            if (VehicleUtils.getHbefaVehicleCategory(ei) == null) {
                vt.setNetworkMode("car");
                VehicleUtils.setHbefaVehicleCategory(ei, "URBAN_BUS");
                VehicleUtils.setHbefaTechnology(ei, "diesel");
                VehicleUtils.setHbefaSizeClass(ei, "not specified");
                VehicleUtils.setHbefaEmissionsConcept(ei, "UBus-d-EU3");
            }
        }
    }

    private void cleanSchedule(TransitSchedule schedule, Network network) {
        int removedStops = 0;
        int removedRoutes = 0;
        int removedLines = 0;
        int removedTransfers = 0;

        Set<Id<TransitStopFacility>> usedInRoutes = new HashSet<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                if (route.getStops() != null) {
                    for (var routeStop : route.getStops()) {
                        usedInRoutes.add(routeStop.getStopFacility().getId());
                    }
                }
            }
        }

        List<Id<TransitStopFacility>> badStopIds = new ArrayList<>();
        for (TransitStopFacility stop : schedule.getFacilities().values()) {
            Id<Link> linkId = stop.getLinkId();
            boolean badLink = linkId == null || !network.getLinks().containsKey(linkId);
            boolean unused = !usedInRoutes.contains(stop.getId());
            if (badLink || unused) {
                badStopIds.add(stop.getId());
            }
        }
        for (Id<TransitStopFacility> id : badStopIds) {
            schedule.removeStopFacility(schedule.getFacilities().get(id));
            removedStops++;
        }

        Set<Id<TransitStopFacility>> validStopIds = new HashSet<>(schedule.getFacilities().keySet());

        var mtt = schedule.getMinimalTransferTimes();
        List<Id<TransitStopFacility>[]> badTransfers = new ArrayList<>();
        var iter = mtt.iterator();
        while (iter.hasNext()) {
            iter.next();
            Id<TransitStopFacility> from = iter.getFromStopId();
            Id<TransitStopFacility> to = iter.getToStopId();
            if (!validStopIds.contains(from) || !validStopIds.contains(to)) {
                @SuppressWarnings("unchecked")
                Id<TransitStopFacility>[] pair = new Id[]{from, to};
                badTransfers.add(pair);
            }
        }
        for (Id<TransitStopFacility>[] pair : badTransfers) {
            mtt.set(pair[0], pair[1], Double.NaN);
            removedTransfers++;
        }

        List<Id<TransitLine>> emptyLineIds = new ArrayList<>();
        for (var entry : schedule.getTransitLines().entrySet()) {
            TransitLine line = entry.getValue();
            List<Id<TransitRoute>> badRouteIds = new ArrayList<>();
            for (var routeEntry : line.getRoutes().entrySet()) {
                TransitRoute route = routeEntry.getValue();
                if (route.getStops() == null || route.getStops().isEmpty()
                        || route.getDepartures() == null || route.getDepartures().isEmpty()) {
                    badRouteIds.add(routeEntry.getKey());
                }
            }
            for (Id<TransitRoute> id : badRouteIds) {
                line.removeRoute(line.getRoutes().get(id));
                removedRoutes++;
            }
            if (line.getRoutes().isEmpty()) {
                emptyLineIds.add(entry.getKey());
            }
        }
        for (Id<TransitLine> id : emptyLineIds) {
            schedule.removeTransitLine(schedule.getTransitLines().get(id));
            removedLines++;
        }

        if (removedStops > 0 || removedRoutes > 0 || removedLines > 0 || removedTransfers > 0) {
            log.warn("{}: {} stops, {} transfers, {} routes, {} lines",
                    L.msg("simulation.transit.cleanup"),
                    removedStops, removedTransfers, removedRoutes, removedLines);
        }
    }

    private void validateSchedule(TransitSchedule schedule, Network network) {
        TransitScheduleValidator.ValidationResult result =
                TransitScheduleValidator.validateAll(schedule, network);
        List<String> errors = result.getErrors();
        if (!errors.isEmpty()) {
            for (String error : errors) {
                log.error("{}: {}", L.msg("simulation.transit.validation.error"), error);
            }
            throw new IllegalStateException(
                    L.msg("simulation.transit.validation.failed") + ": " + errors.size()
                    + " — " + errors.stream().collect(Collectors.joining("; ")));
        }
        List<String> warnings = result.getWarnings();
        for (String warning : warnings) {
            log.warn("{}: {}", L.msg("simulation.transit.validation.warning"), warning);
        }
    }
}
