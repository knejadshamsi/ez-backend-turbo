package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.mobsim.qsim.interfaces.MobsimVehicle;
import org.matsim.core.mobsim.qsim.interfaces.Netsim;
import org.matsim.core.mobsim.qsim.qnetsimengine.QVehicle;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.router.DijkstraFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.vehicles.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implements the Zero Emission Zone (ZEZ) policy for transportation simulation.
 * 
 * This class manages:
 * - Dynamic routing for vehicles based on emission levels
 * - Travel disutility calculations considering ZEZ restrictions
 * - Vehicle rerouting and alternative route suggestions
 * - Enforcement of ZEZ access rules
 * 
 * Key responsibilities:
 * - Modify vehicle routes to comply with ZEZ restrictions
 * - Apply economic incentives and penalties for different vehicle types
 * - Track and record ZEZ violations and rerouting events
 */
public class ZeroEmissionZonePolicy implements MobsimBeforeSimStepListener, 
    MobsimInitializedListener, TravelDisutilityFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZonePolicy.class);

    // Core simulation components
    private final ZeroEmissionZone zez;
    private final Network network;
    private final StatisticsCollector statistics;
    private final TravelTime travelTime;
    private Netsim mobsim;
    private LeastCostPathCalculator pathCalculator;

    // Cost multipliers for different vehicle types and situations
    private static final double EV_MULTIPLIER = 1.0;        // Base cost for electric vehicles
    private static final double LEV_MULTIPLIER = 1.5;       // Slightly higher cost for low emission vehicles
    private static final double HEV_MULTIPLIER = Double.POSITIVE_INFINITY;    // Prohibit hybrid vehicles

    /**
     * Constructs a Zero Emission Zone Policy with specific simulation components.
     * 
     * @param zez Zero Emission Zone configuration
     * @param network Transportation network
     * @param statistics Collector for policy-related statistics
     * @param travelTime Travel time calculator
     */
    public ZeroEmissionZonePolicy(
            ZeroEmissionZone zez,
            Network network,
            StatisticsCollector statistics,
            TravelTime travelTime) {
        this.zez = zez;
        this.network = network;
        this.statistics = statistics;
        this.travelTime = travelTime;
    }

    /**
     * Initializes the mobility simulation and path calculator.
     * 
     * @param e Mobility simulation initialization event
     */
    @Override
    public void notifyMobsimInitialized(MobsimInitializedEvent e) {
        this.mobsim = (Netsim) e.getQueueSimulation();
        initializePathCalculator();
        logger.info("ZeroEmissionZonePolicy initialized");
    }

    /**
     * Creates a least-cost path calculator for dynamic routing.
     */
    private void initializePathCalculator() {
        TravelDisutility travelDisutility = createTravelDisutility(travelTime);
        this.pathCalculator = new DijkstraFactory().createPathCalculator(
            network,
            travelDisutility,
            travelTime
        );
        logger.info("Path calculator initialized");
    }

    /**
     * Processes vehicles before each simulation step.
     * Checks and enforces ZEZ policies for vehicles in the network.
     * 
     * @param e Mobility simulation step event
     */
    @Override
    public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent e) {
        if (mobsim == null) return;

        double now = e.getSimulationTime();
        LocalTime currentTime = LocalTime.of(
            (int) (now / 3600) % 24,
            (int) ((now % 3600) / 60)
        );

        processVehicles(currentTime, now);
    }

    /**
     * Processes all vehicles in the network, applying ZEZ policies.
     * 
     * @param currentTime Current simulation time
     * @param now Simulation time in seconds
     */
    private void processVehicles(LocalTime currentTime, double now) {
        Collection<MobsimVehicle> vehicles = getAllVehiclesInNetwork();
        for (MobsimVehicle vehicle : vehicles) {
            if (vehicle instanceof QVehicle) {
                QVehicle qVehicle = (QVehicle) vehicle;
                if (qVehicle.getDriver() == null) continue;

                Id<Person> driverId = qVehicle.getDriver().getId();
                Person person = mobsim.getScenario().getPopulation().getPersons().get(driverId);
                Link currentLink = qVehicle.getCurrentLink();

                if (currentLink != null) {
                    String vehicleType = getVehicleType(person);
                    // Check if vehicle is approaching or in ZEZ
                    if (isApproachingZEZ(currentLink) || zez.isZoneLink(currentLink.getId())) {
                        processVehicle(person, vehicleType, currentLink, currentTime, now);
                    }
                }
            }
        }
    }

    /**
     * Determines if a vehicle is approaching the Zero Emission Zone.
     * 
     * @param currentLink Current link of the vehicle
     * @return true if any outgoing link is in the ZEZ, false otherwise
     */
    private boolean isApproachingZEZ(Link currentLink) {
        return currentLink.getToNode().getOutLinks().values().stream()
            .anyMatch(outLink -> zez.isZoneLink(outLink.getId()));
    }

    /**
     * Retrieves all non-parked vehicles in the network.
     * 
     * @return Collection of mobile vehicles
     */
    private Collection<MobsimVehicle> getAllVehiclesInNetwork() {
        return mobsim.getNetsimNetwork().getNetsimLinks().values()
                .stream()
                .flatMap(link -> link.getAllNonParkedVehicles().stream())
                .collect(Collectors.toList());
    }

    /**
     * Processes a vehicle's movement relative to ZEZ policies.
     * Handles rerouting and alternative route suggestions based on vehicle type.
     * 
     * @param person Vehicle driver
     * @param vehicleType Type of vehicle
     * @param currentLink Current link
     * @param currentTime Current simulation time
     * @param now Simulation time in seconds
     */
    private void processVehicle(Person person, String vehicleType, Link currentLink, 
            LocalTime currentTime, double now) {
        
        // Strict enforcement for hybrid vehicles during ZEZ operating hours
        if (vehicleType.equals("hev_car") && 
            zez.isOperatingHours(currentTime)) {
            
            // Record and attempt to reroute hybrid vehicles
            statistics.recordViolation(person.getId(), currentLink.getId());
            
            boolean reroutingSuccess = replanRoute(person, currentLink, now);
            
            if (reroutingSuccess) {
                logger.info("Successfully rerouted HEV {} away from ZEZ at {}", 
                    person.getId(), currentTime);
            } else {
                logger.error("Failed to reroute HEV {} at {}, vehicle may be stuck", 
                    person.getId(), currentTime);
            }
        } 
        // Proactive routing for low emission vehicles
        else if (vehicleType.equals("lev_car") && 
                 zez.isOperatingHours(currentTime) && 
                 isApproachingZEZ(currentLink)) {
            
            // Suggest alternative route with potential rewards
            boolean reroutingSuccess = suggestAlternativeRoute(person, currentLink, now);
            if (reroutingSuccess) {
                logger.info("Suggested alternative route for LEV {} at {}", 
                    person.getId(), currentTime);
                statistics.recordRerouting(person.getId(), currentLink.getId());
            }
        }
    }

    /**
     * Suggests an alternative route for a low emission vehicle.
     * 
     * @param person Vehicle driver
     * @param currentLink Current link
     * @param now Simulation time in seconds
     * @return true if alternative route found and applied, false otherwise
     */
    private boolean suggestAlternativeRoute(Person person, Link currentLink, double now) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) return false;

        Activity[] activities = findCurrentAndNextActivity(plan, currentLink);
        Activity currentActivity = activities[0];
        Activity nextActivity = activities[1];

        if (currentActivity == null || nextActivity == null) return false;

        Link fromLink = network.getLinks().get(currentActivity.getLinkId());
        Link toLink = network.getLinks().get(nextActivity.getLinkId());
        
        if (fromLink == null || toLink == null) return false;

        LeastCostPathCalculator.Path path = pathCalculator.calcLeastCostPath(
            fromLink.getToNode(),
            toLink.getFromNode(),
            now,
            person,
            null
        );

        if (path != null && !path.links.isEmpty() && hasAlternativeRoute(path)) {
            updateRouteInPlan(path, person.getSelectedPlan(), currentActivity);
            return true;
        }
        return false;
    }

    /**
     * Checks if the calculated path includes an alternative route.
     * 
     * @param path Calculated least-cost path
     * @return true if path includes an alternative route link, false otherwise
     */
    private boolean hasAlternativeRoute(LeastCostPathCalculator.Path path) {
        return path.links.stream()
            .anyMatch(link -> zez.isAlternativeLink(link.getId()));
    }

    /**
     * Creates a travel disutility calculator that considers ZEZ policies.
     * 
     * @param timeCalculator Travel time calculator
     * @return Custom TravelDisutility implementation
     */
    @Override
    public TravelDisutility createTravelDisutility(final TravelTime timeCalculator) {
        return new TravelDisutility() {
            @Override
            public double getLinkTravelDisutility(Link link, double time, 
                    Person person, Vehicle vehicle) {
                
                double baseCost = getLinkMinimumTravelDisutility(link);
                String vehicleType = getVehicleType(person);
                
                // Enhanced alternative route incentives using config parameters
                if (!zez.isZoneLink(link.getId())) {
                    if (zez.isAlternativeLink(link.getId())) {
                        if (vehicleType.equals("lev_car")) {
                            // Apply travel time reduction and alternative route reward for LEVs
                            double timeReduction = zez.getConfig().getBypassRouteTravelTimeReductionValue();
                            double alternativeReward = zez.getConfig().getLevAlternativeRewardValue();
                            // Convert reward to a cost reduction factor (higher reward = lower cost)
                            double rewardFactor = Math.max(0.1, 1.0 - (alternativeReward / 1000.0));
                            return baseCost * timeReduction * rewardFactor;
                        }
                        if (vehicleType.equals("hev_car")) {
                            return baseCost * zez.getConfig().getBypassRouteTravelTimeReductionValue();
                        }
                        return baseCost;
                    }
                    return baseCost;
                }

                LocalTime currentTime = LocalTime.of(
                    (int) (time / 3600) % 24,
                    (int) ((time % 3600) / 60)
                );

                if (!zez.isOperatingHours(currentTime)) {
                    return baseCost;
                }

                // Apply vehicle-specific multipliers and charges
                double multiplier;
                if ("ev_car".equals(vehicleType)) {
                    multiplier = EV_MULTIPLIER;
                } else if ("lev_car".equals(vehicleType)) {
                    multiplier = LEV_MULTIPLIER;
                    // Add base charge and any peak hour surcharge
                    if (zez.getConfig().isEnableGraduatedChargingValue()) {
                        multiplier += zez.getConfig().getBaseChargeValue() / 1000.0;
                        if (zez.isPeakHour(currentTime)) {
                            multiplier += zez.getConfig().getPeakHourSurchargeValue() / 1000.0;
                        }
                    }
                } else if ("hev_car".equals(vehicleType)) {
                    multiplier = HEV_MULTIPLIER;
                } else {
                    multiplier = 1.0;
                }

                return baseCost * multiplier;
            }

            @Override
            public double getLinkMinimumTravelDisutility(Link link) {
                double timeCost = timeCalculator.getLinkTravelTime(link, 0.0, null, null);
                double distanceCost = link.getLength() * 0.00001; // Small distance-based cost
                
                // Apply capacity increase for alternative routes
                if (zez.isAlternativeLink(link.getId())) {
                    double capacityIncrease = zez.getConfig().getBypassRouteCapacityIncreaseValue();
                    timeCost /= capacityIncrease; // Lower time cost for higher capacity
                }
                
                return timeCost + distanceCost;
            }
        };
    }

    /**
     * Replans the route for a vehicle to avoid the Zero Emission Zone.
     * 
     * @param person Vehicle driver
     * @param currentLink Current link
     * @param now Simulation time in seconds
     * @return true if rerouting is successful, false otherwise
     */
    private boolean replanRoute(Person person, Link currentLink, double now) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) return false;

        Activity[] activities = findCurrentAndNextActivity(plan, currentLink);
        Activity currentActivity = activities[0];
        Activity nextActivity = activities[1];

        if (currentActivity == null || nextActivity == null) {
            logger.warn("Could not find activities for replanning route of person {}", 
                person.getId());
            return false;
        }

        Link fromLink = network.getLinks().get(currentActivity.getLinkId());
        Link toLink = network.getLinks().get(nextActivity.getLinkId());
        
        if (fromLink == null || toLink == null) {
            logger.error("Could not find links for route calculation");
            return false;
        }

        LeastCostPathCalculator.Path path = pathCalculator.calcLeastCostPath(
            fromLink.getToNode(),
            toLink.getFromNode(),
            now,
            person,
            null
        );

        if (path != null && !path.links.isEmpty()) {
            updateRouteInPlan(path, person.getSelectedPlan(), currentActivity);
            logger.info("Rerouted vehicle {} to bypass ZEZ", person.getId());
            return true;
        }
        return false;
    }

    /**
     * Finds the current and next activities in a person's plan relative to a current link.
     * 
     * @param plan Person's travel plan
     * @param currentLink Current link
     * @return Array containing current and next activities
     */
    private Activity[] findCurrentAndNextActivity(Plan plan, Link currentLink) {
        Activity currentActivity = null;
        Activity nextActivity = null;

        List<? extends org.matsim.api.core.v01.population.PlanElement> planElements = 
            plan.getPlanElements();
            
        for (int i = 0; i < planElements.size(); i++) {
            if (planElements.get(i) instanceof Activity) {
                Activity act = (Activity) planElements.get(i);
                if (act.getLinkId().equals(currentLink.getId())) {
                    currentActivity = act;
                    // Find next activity
                    for (int j = i + 1; j < planElements.size(); j++) {
                        if (planElements.get(j) instanceof Activity) {
                            nextActivity = (Activity) planElements.get(j);
                            break;
                        }
                    }
                    break;
                }
            }
        }

        return new Activity[]{currentActivity, nextActivity};
    }

    /**
     * Updates the route in a person's plan with a new calculated path.
     * 
     * @param path Newly calculated least-cost path
     * @param plan Person's travel plan
     * @param currentActivity Current activity
     */
    private void updateRouteInPlan(LeastCostPathCalculator.Path path, Plan plan, 
            Activity currentActivity) {
        
        List<Id<Link>> linkIds = path.links.stream()
            .map(Link::getId)
            .collect(Collectors.toList());
            
        NetworkRoute route = RouteUtils.createNetworkRoute(linkIds, network);
        
        route.setTravelTime(path.travelTime);
        route.setDistance(path.links.stream().mapToDouble(Link::getLength).sum());
        
        // Find and update the leg following the current activity
        boolean foundActivity = false;
        for (org.matsim.api.core.v01.population.PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity) {
                if (pe == currentActivity) {
                    foundActivity = true;
                }
            } else if (pe instanceof Leg && foundActivity) {
                ((Leg) pe).setRoute(route);
                break;
            }
        }
    }

    /**
     * Determines the vehicle type for a given person.
     * 
     * @param person Vehicle driver
     * @return Vehicle type string
     */
    private String getVehicleType(Person person) {
        if (person == null) return "";
        
        // First try to get from person attributes
        Object vehicleTypeAttr = person.getAttributes().getAttribute("vehicleType");
        if (vehicleTypeAttr != null) {
            return vehicleTypeAttr.toString();
        }
        
        // Fallback to ID-based detection
        String personId = person.getId().toString();
        if (personId.contains("ev")) {
            return "ev_car";
        } else if (personId.contains("lev")) {
            return "lev_car";
        } else if (personId.contains("hev")) {
            return "hev_car";
        }
        
        return "";
    }
}
