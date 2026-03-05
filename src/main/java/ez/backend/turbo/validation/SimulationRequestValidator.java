package ez.backend.turbo.validation;

import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.endpoints.SimulationRequest.*;
import ez.backend.turbo.services.SourceRegistry;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class SimulationRequestValidator {

    private static final Logger log = LogManager.getLogger(SimulationRequestValidator.class);

    private static final Set<String> VALID_VEHICLE_TYPES = Set.of(
            "zeroEmission", "nearZeroEmission", "lowEmission", "midEmission", "highEmission");
    private static final Set<String> VALID_TRIP_TYPES = Set.of("start", "end", "pass");
    private static final Set<Integer> VALID_TIERS = Set.of(1, 2, 3);
    private static final String TIME_PATTERN = "^([01]\\d|2[0-3]):[0-5]\\d$";
    private static final double EARTH_RADIUS = 6378137.0;
    private static final double ZONE_MIN_AREA_SQM = 500000;
    private static final double ZONE_MAX_AREA_SQM = 5000000;
    private static final double SIM_AREA_MIN_SQM = 500000;
    private static final double SIM_AREA_MAX_SQM = 6000000;

    private final SourceRegistry sourceRegistry;

    public SimulationRequestValidator(SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
    }

    private static final double[][] MTL_BOUNDARY = {
            {-73.61197208186829, 45.41263857780996},
            {-73.52288866657636, 45.460662325514306},
            {-73.54276354237754, 45.52113298596623},
            {-73.49833116562328, 45.58727093205829},
            {-73.48051362666197, 45.65133852134059},
            {-73.48746263932175, 45.6803086372245},
            {-73.47404385625444, 45.70374168629593},
            {-73.49944369563231, 45.69888850379368},
            {-73.5138209632041, 45.701398823201345},
            {-73.54473208848428, 45.67478370290971},
            {-73.6190146376082, 45.63977978412598},
            {-73.64082016009277, 45.61430864477978},
            {-73.64574663581253, 45.59983053862982},
            {-73.66782369637252, 45.57333109890186},
            {-73.67397851325528, 45.55928038695987},
            {-73.68187273490963, 45.55150414931842},
            {-73.70609060134132, 45.545694691369846},
            {-73.73539288172076, 45.529949916543444},
            {-73.76498414196323, 45.51162949431537},
            {-73.81497202590612, 45.51687179529219},
            {-73.84607368634126, 45.517948201616974},
            {-73.85895763384994, 45.50467075449677},
            {-73.85862079862109, 45.49510905306582},
            {-73.90468980997117, 45.46423059431703},
            {-73.92637470205938, 45.47526334590961},
            {-73.97888615643005, 45.46095017063175},
            {-73.97973654435545, 45.419779546162886},
            {-73.95635087641645, 45.402466983784336},
            {-73.91191810733349, 45.40216844479252},
            {-73.78627329140986, 45.43529664210331},
            {-73.63795532007236, 45.412836680990864},
            {-73.61197208186829, 45.41263857780996}
    };

    public ValidationResult validate(SimulationRequest request) {
        log.info(L.msg("validation.started"));
        ValidationResult result = new ValidationResult();

        validateZones(request, result);
        validateCustomSimulationAreas(request, result);
        validateScaledSimulationAreas(request, result);
        validateSources(request, result);
        validateSimulationOptions(request, result);
        validateCarDistribution(request, result);
        validateModeUtilities(request, result);

        if (!result.hasErrors()) {
            log.info(L.msg("validation.passed"));
        }
        return result;
    }

    private boolean isValidUuidV4(String value) {
        try {
            return UUID.fromString(value).version() == 4;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void validateZones(SimulationRequest request, ValidationResult result) {
        if (request.getZones() == null) {
            result.add("zones", ZoneValidationError.LIST_REQUIRED,
                    String.format(L.msg("validation.required"), "zones"));
            return;
        }
        if (request.getZones().isEmpty()) {
            result.add("zones", ZoneValidationError.LIST_EMPTY,
                    L.msg("validation.zones.empty"));
            return;
        }
        for (int i = 0; i < request.getZones().size(); i++) {
            validateZone(request.getZones().get(i), i, result);
        }
    }

    private void validateZone(Zone zone, int index, ValidationResult result) {
        String zp = "zones[" + index + "]";

        if (zone.getId() == null || zone.getId().isBlank()) {
            result.add(zp + ".id", ZoneValidationError.ID_REQUIRED,
                    String.format(L.msg("validation.zone.id.required"),
                            String.format(L.msg("validation.ctx.zone.index"), index)));
        } else if (!isValidUuidV4(zone.getId())) {
            result.add(zp + ".id", ZoneValidationError.ID_INVALID_FORMAT,
                    String.format(L.msg("validation.zone.id.format"),
                            String.format(L.msg("validation.ctx.zone.index"), index)));
        }

        if (zone.getCoords() == null) {
            result.add(zp + ".coords", ZoneValidationError.COORDS_REQUIRED,
                    String.format(L.msg("validation.zone.coords.required"), zp));
        } else {
            int before = result.errors().size();
            validatePolygon(zone.getCoords(), zp + ".coords", result);
            if (result.errors().size() == before) {
                validateGeo(zone.getCoords(), zp + ".coords", ZONE_MIN_AREA_SQM, ZONE_MAX_AREA_SQM, result);
            }
        }

        if (zone.getTrip() == null || zone.getTrip().isEmpty()) {
            result.add(zp + ".trip", ZoneValidationError.TRIP_REQUIRED,
                    String.format(L.msg("validation.zone.trip.required"), zp));
        } else {
            for (int t = 0; t < zone.getTrip().size(); t++) {
                String tripType = zone.getTrip().get(t);
                if (tripType == null || !VALID_TRIP_TYPES.contains(tripType)) {
                    result.add(zp + ".trip[" + t + "]", ZoneValidationError.TRIP_INVALID_VALUE,
                            String.format(L.msg("validation.zone.trip.invalid"),
                                    zp, tripType, VALID_TRIP_TYPES));
                }
            }
        }

        if (zone.getPolicies() == null || zone.getPolicies().isEmpty()) {
            result.add(zp + ".policies", ZoneValidationError.POLICIES_REQUIRED,
                    String.format(L.msg("validation.zone.policies.required"), zp));
        } else {
            for (int i = 0; i < zone.getPolicies().size(); i++) {
                validatePolicy(zone.getPolicies().get(i), zp + ".policies[" + i + "]", result);
            }
        }
    }

    private void validatePolygon(List<List<double[]>> rings, String origin, ValidationResult result) {
        for (int r = 0; r < rings.size(); r++) {
            List<double[]> ring = rings.get(r);
            String rp = origin + "[" + r + "]";
            if (ring.size() < 4) {
                result.add(rp, PolygonValidationError.TOO_FEW_POINTS,
                        String.format(L.msg("validation.polygon.min.points"), origin, r));
                continue;
            }
            for (int c = 0; c < ring.size(); c++) {
                double[] coord = ring.get(c);
                if (coord.length != 2) {
                    result.add(rp + "[" + c + "]", PolygonValidationError.INVALID_POINT_SIZE,
                            String.format(L.msg("validation.polygon.point.size"), origin, r, c));
                }
            }
            double[] first = ring.get(0);
            double[] last = ring.get(ring.size() - 1);
            if (first[0] != last[0] || first[1] != last[1]) {
                result.add(rp, PolygonValidationError.NOT_CLOSED,
                        String.format(L.msg("validation.polygon.not.closed"), origin, r));
            }
        }
    }

    private void validatePolicy(Policy policy, String origin, ValidationResult result) {
        if (policy.getVehicleType() == null || !VALID_VEHICLE_TYPES.contains(policy.getVehicleType())) {
            result.add(origin + ".vehicleType", PolicyValidationError.VEHICLE_TYPE_INVALID,
                    String.format(L.msg("validation.policy.vehicle.type"), origin, VALID_VEHICLE_TYPES));
        }
        if (!VALID_TIERS.contains(policy.getTier())) {
            result.add(origin + ".tier", PolicyValidationError.TIER_INVALID,
                    String.format(L.msg("validation.policy.tier"), origin));
        }
        if (policy.getPeriod() == null || policy.getPeriod().size() != 2) {
            result.add(origin + ".period", PolicyValidationError.PERIOD_REQUIRED,
                    String.format(L.msg("validation.policy.period.size"), origin));
        } else {
            for (int i = 0; i < policy.getPeriod().size(); i++) {
                String time = policy.getPeriod().get(i);
                if (time == null || !time.matches(TIME_PATTERN)) {
                    result.add(origin + ".period[" + i + "]", PolicyValidationError.PERIOD_INVALID_FORMAT,
                            String.format(L.msg("validation.policy.period.format"), origin, time));
                }
            }
            if (policy.getPeriod().get(0).compareTo(policy.getPeriod().get(1)) >= 0) {
                result.add(origin + ".period", PolicyValidationError.PERIOD_INVALID_ORDER,
                        String.format(L.msg("validation.policy.period.order"), origin));
            }
        }
        if (policy.getTier() == 2) {
            if (policy.getPenalty() == null || policy.getPenalty() <= 0) {
                result.add(origin + ".penalty", PolicyValidationError.PENALTY_REQUIRED,
                        String.format(L.msg("validation.policy.penalty"), origin));
            } else if (policy.getPenalty() >= 10000) {
                result.add(origin + ".penalty", PolicyValidationError.PENALTY_TOO_HIGH,
                        String.format(L.msg("validation.policy.penalty.max"), origin));
            }
            if (policy.getInterval() == null || policy.getInterval() <= 0) {
                result.add(origin + ".interval", PolicyValidationError.INTERVAL_REQUIRED,
                        String.format(L.msg("validation.policy.interval"), origin));
            }
        } else {
            if (policy.getPenalty() != null) {
                result.add(origin + ".penalty", PolicyValidationError.PENALTY_FORBIDDEN,
                        String.format(L.msg("validation.policy.penalty.forbidden"), origin));
            }
            if (policy.getInterval() != null) {
                result.add(origin + ".interval", PolicyValidationError.INTERVAL_FORBIDDEN,
                        String.format(L.msg("validation.policy.interval.forbidden"), origin));
            }
        }
    }

    private void validateCustomSimulationAreas(SimulationRequest request, ValidationResult result) {
        if (request.getCustomSimulationAreas() == null) {
            result.add("customSimulationAreas", AreaValidationError.REQUIRED,
                    String.format(L.msg("validation.required"), "customSimulationAreas"));
            return;
        }
        for (int i = 0; i < request.getCustomSimulationAreas().size(); i++) {
            CustomSimulationArea area = request.getCustomSimulationAreas().get(i);
            String ap = "customSimulationAreas[" + i + "]";

            if (area.getId() == null || area.getId().isBlank()) {
                result.add(ap + ".id", AreaValidationError.ID_REQUIRED,
                        String.format(L.msg("validation.area.id.required"), ap));
            } else if (!isValidUuidV4(area.getId())) {
                result.add(ap + ".id", AreaValidationError.ID_INVALID_FORMAT,
                        String.format(L.msg("validation.area.id.format"), ap));
            }

            if (area.getCoords() == null) {
                result.add(ap + ".coords", AreaValidationError.COORDS_REQUIRED,
                        String.format(L.msg("validation.area.coords.required"), ap));
            } else {
                int before = result.errors().size();
                validatePolygon(area.getCoords(), ap + ".coords", result);
                if (result.errors().size() == before) {
                    validateGeo(area.getCoords(), ap + ".coords", SIM_AREA_MIN_SQM, SIM_AREA_MAX_SQM, result);
                }
            }
        }
    }

    private void validateScaledSimulationAreas(SimulationRequest request, ValidationResult result) {
        if (request.getScaledSimulationAreas() == null) {
            result.add("scaledSimulationAreas", AreaValidationError.REQUIRED,
                    String.format(L.msg("validation.required"), "scaledSimulationAreas"));
            return;
        }
        Set<String> zoneIds = new HashSet<>();
        if (request.getZones() != null) {
            for (Zone zone : request.getZones()) {
                if (zone.getId() != null) zoneIds.add(zone.getId());
            }
        }

        for (int i = 0; i < request.getScaledSimulationAreas().size(); i++) {
            ScaledSimulationArea area = request.getScaledSimulationAreas().get(i);
            String ap = "scaledSimulationAreas[" + i + "]";

            if (area.getId() == null || area.getId().isBlank()) {
                result.add(ap + ".id", AreaValidationError.ID_REQUIRED,
                        String.format(L.msg("validation.area.id.required"), ap));
            } else if (!isValidUuidV4(area.getId())) {
                result.add(ap + ".id", AreaValidationError.ID_INVALID_FORMAT,
                        String.format(L.msg("validation.area.id.format"), ap));
            }

            if (area.getZoneId() == null || area.getZoneId().isBlank()) {
                result.add(ap + ".zoneId", AreaValidationError.ZONE_REF_REQUIRED,
                        String.format(L.msg("validation.area.zoneid.required"), ap));
            } else if (!isValidUuidV4(area.getZoneId())) {
                result.add(ap + ".zoneId", AreaValidationError.ZONE_REF_INVALID_FORMAT,
                        String.format(L.msg("validation.area.zoneid.format"), ap));
            } else if (!zoneIds.contains(area.getZoneId())) {
                result.add(ap + ".zoneId", AreaValidationError.ZONE_REF_NOT_FOUND,
                        String.format(L.msg("validation.area.zoneid.ref"), ap, area.getZoneId()));
            }

            if (area.getCoords() == null) {
                result.add(ap + ".coords", AreaValidationError.COORDS_REQUIRED,
                        String.format(L.msg("validation.area.coords.required"), ap));
            } else {
                int before = result.errors().size();
                validatePolygon(area.getCoords(), ap + ".coords", result);
                if (result.errors().size() == before) {
                    validateGeo(area.getCoords(), ap + ".coords", SIM_AREA_MIN_SQM, SIM_AREA_MAX_SQM, result);
                }
            }
        }
    }

    private void validateGeo(List<List<double[]>> rings, String origin,
                             double minAreaSqm, double maxAreaSqm, ValidationResult result) {
        double areaSqm = geodesicRingArea(rings.get(0));
        double areaKm2 = areaSqm / 1_000_000.0;
        double minKm2 = minAreaSqm / 1_000_000.0;
        double maxKm2 = maxAreaSqm / 1_000_000.0;

        if (areaSqm < minAreaSqm) {
            result.add(origin, PolygonValidationError.AREA_TOO_SMALL,
                    String.format(L.msg("validation.geo.area.below"),
                            origin, String.format("%.2f", areaKm2), String.format("%.1f", minKm2)));
        }
        if (areaSqm > maxAreaSqm) {
            result.add(origin, PolygonValidationError.AREA_TOO_LARGE,
                    String.format(L.msg("validation.geo.area.above"),
                            origin, String.format("%.2f", areaKm2), String.format("%.1f", maxKm2)));
        }

        for (double[] coord : rings.get(0)) {
            if (!isPointInPolygon(coord[0], coord[1], MTL_BOUNDARY)) {
                result.add(origin, PolygonValidationError.OUTSIDE_BOUNDARY,
                        String.format(L.msg("validation.geo.outside.boundary"),
                                origin, String.valueOf(coord[0]), String.valueOf(coord[1])));
            }
        }
    }

    private double geodesicRingArea(List<double[]> ring) {
        int len = ring.size();
        if (len < 3) return 0;

        double sum = 0;
        for (int i = 0; i < len - 1; i++) {
            double[] p1 = ring.get(i == 0 ? len - 2 : i - 1);
            double[] p2 = ring.get(i);
            double[] p3 = ring.get(i + 1);
            sum += (Math.toRadians(p3[0]) - Math.toRadians(p1[0])) * Math.sin(Math.toRadians(p2[1]));
        }
        return Math.abs(sum * EARTH_RADIUS * EARTH_RADIUS / 2.0);
    }

    private boolean isPointInPolygon(double lng, double lat, double[][] boundary) {
        boolean inside = false;
        int n = boundary.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = boundary[i][0], yi = boundary[i][1];
            double xj = boundary[j][0], yj = boundary[j][1];

            if ((yi > lat) != (yj > lat)
                    && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private void validateSources(SimulationRequest request, ValidationResult result) {
        if (request.getSources() == null) {
            result.add("sources", SourceValidationError.REQUIRED,
                    String.format(L.msg("validation.required"), "sources"));
            return;
        }
        resolveSource(request.getSources().getPopulation(), "population", "sources.population", result);
        resolveSource(request.getSources().getNetwork(), "network", "sources.network", result);
        resolveSource(request.getSources().getPublicTransport(), "publicTransport", "sources.publicTransport", result);
    }

    private void resolveSource(DataSource ds, String type, String origin, ValidationResult result) {
        if (ds == null) {
            result.add(origin, SourceValidationError.REQUIRED,
                    String.format(L.msg("validation.required"), origin));
            return;
        }
        try {
            sourceRegistry.resolve(type, ds.getYear(), ds.getName());
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("year")) {
                result.add(origin + ".year", SourceValidationError.YEAR_UNAVAILABLE, msg);
            } else if (msg != null && msg.contains("not available")) {
                result.add(origin + ".name", SourceValidationError.NAME_UNAVAILABLE, msg);
            } else {
                result.add(origin, SourceValidationError.REQUIRED, msg);
            }
        }
    }

    private void validateSimulationOptions(SimulationRequest request, ValidationResult result) {
        if (request.getSimulationOptions() == null) {
            result.add("simulationOptions", OptionsValidationError.REQUIRED,
                    String.format(L.msg("validation.required"), "simulationOptions"));
            return;
        }
        if (request.getSimulationOptions().getIterations() < 1 || request.getSimulationOptions().getIterations() > 10) {
            result.add("simulationOptions.iterations", OptionsValidationError.ITERATIONS_OUT_OF_RANGE,
                    L.msg("validation.options.iterations"));
        }
        if (request.getSimulationOptions().getPercentage() < 1 || request.getSimulationOptions().getPercentage() > 10) {
            result.add("simulationOptions.percentage", OptionsValidationError.PERCENTAGE_OUT_OF_RANGE,
                    L.msg("validation.options.percentage"));
        }
    }

    private void validateCarDistribution(SimulationRequest request, ValidationResult result) {
        if (request.getCarDistribution() == null) {
            result.add("carDistribution", CarDistributionValidationError.REQUIRED,
                    String.format(L.msg("validation.required"), "carDistribution"));
            return;
        }
        CarDistribution cd = request.getCarDistribution();
        if (cd.getZeroEmission() < 0) {
            result.add("carDistribution.zeroEmission", CarDistributionValidationError.NEGATIVE_VALUE,
                    String.format(L.msg("validation.car.negative"), "zeroEmission"));
        }
        if (cd.getNearZeroEmission() < 0) {
            result.add("carDistribution.nearZeroEmission", CarDistributionValidationError.NEGATIVE_VALUE,
                    String.format(L.msg("validation.car.negative"), "nearZeroEmission"));
        }
        if (cd.getLowEmission() < 0) {
            result.add("carDistribution.lowEmission", CarDistributionValidationError.NEGATIVE_VALUE,
                    String.format(L.msg("validation.car.negative"), "lowEmission"));
        }
        if (cd.getMidEmission() < 0) {
            result.add("carDistribution.midEmission", CarDistributionValidationError.NEGATIVE_VALUE,
                    String.format(L.msg("validation.car.negative"), "midEmission"));
        }
        if (cd.getHighEmission() < 0) {
            result.add("carDistribution.highEmission", CarDistributionValidationError.NEGATIVE_VALUE,
                    String.format(L.msg("validation.car.negative"), "highEmission"));
        }
        double sum = cd.getZeroEmission() + cd.getNearZeroEmission() + cd.getLowEmission()
                + cd.getMidEmission() + cd.getHighEmission();
        if (sum < 99.99 || sum > 100.01) {
            result.add("carDistribution", CarDistributionValidationError.SUM_INVALID,
                    String.format(L.msg("validation.car.sum"), String.format("%.1f", sum)));
        }
    }

    private void validateModeUtilities(SimulationRequest request, ValidationResult result) {
        if (request.getModeUtilities() == null) {
            result.add("modeUtilities", ModeUtilityValidationError.REQUIRED,
                    String.format(L.msg("validation.required"), "modeUtilities"));
            return;
        }
        ModeUtilities mu = request.getModeUtilities();
        validateModeValue(mu.getWalk(), "modeUtilities.walk", result);
        validateModeValue(mu.getBike(), "modeUtilities.bike", result);
        validateModeValue(mu.getCar(), "modeUtilities.car", result);
        validateModeValue(mu.getEv(), "modeUtilities.ev", result);
        validateModeValue(mu.getSubway(), "modeUtilities.subway", result);
        validateModeValue(mu.getBus(), "modeUtilities.bus", result);
    }

    private void validateModeValue(double value, String origin, ValidationResult result) {
        if (value % 1 != 0 || value < -10 || value > 10) {
            result.add(origin, ModeUtilityValidationError.OUT_OF_RANGE,
                    String.format(L.msg("validation.mode.range"), origin));
        }
    }
}
