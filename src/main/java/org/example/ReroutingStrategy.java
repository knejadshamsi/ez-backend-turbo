package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.population.PopulationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class ReroutingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ReroutingStrategy.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    private final Network network;
    private final ZeroEmissionZone zeroEmissionZone;
    private final Map<Link, Map<ZeroEmissionZone.VehicleCategory, Double>> linkCosts = new HashMap<>();
    
    private int reroutedPlansCount = 0;

    public ReroutingStrategy(Network network, ZeroEmissionZoneConfigGroup config) {
        this.network = network;
        this.zeroEmissionZone = new ZeroEmissionZone(network, config);
        initializeLinkCosts();
    }

    private void initializeLinkCosts() {
        for (Link link : network.getLinks().values()) {
            Map<ZeroEmissionZone.VehicleCategory, Double> categoryCosts = new HashMap<>();
            double baseCost = calculateBaseCost(link);

            for (ZeroEmissionZone.VehicleCategory category : ZeroEmissionZone.VehicleCategory.values()) {
                double categoryCost = baseCost;
                
                if (zeroEmissionZone.isZoneLink(link.getId())) {
                    switch (category) {
                        case ELECTRIC:
                            // EVs get a benefit for using the zone
                            categoryCost *= 0.8;
                            break;
                        case LOW_EMISSION:
                            // LEVs get a moderate penalty
                            categoryCost *= 1.2;
                            break;
                        case HEAVY_EMISSION:
                            // HEVs get effectively infinite cost during operating hours
                            categoryCost = Double.POSITIVE_INFINITY;
                            break;
                    }
                } else if (zeroEmissionZone.isAlternativeLink(link.getId())) {
                    // Make alternative routes more attractive for non-EVs
                    switch (category) {
                        case LOW_EMISSION:
                            // Significant incentive for LEVs to use alternative routes
                            categoryCost *= 0.6;
                            break;
                        case HEAVY_EMISSION:
                            // Strong incentive for HEVs to use alternative routes
                            categoryCost *= 0.5;
                            break;
                        default:
                            break;
                    }
                }
                
                categoryCosts.put(category, categoryCost);
            }
            
            linkCosts.put(link, categoryCosts);
        }
    }

    private double calculateBaseCost(Link link) {
        // Base cost considers travel time and distance with preference for faster routes
        return link.getLength() / Math.max(link.getFreespeed(), 1.0);
    }

    public void processAgent(Person person) {
        Plan selectedPlan = person.getSelectedPlan();
        if (selectedPlan == null) {
            return;
        }

        Plan newPlan = createNewPlan(person);
        if (newPlan != null) {
            person.addPlan(newPlan);
            person.setSelectedPlan(newPlan);
            reroutedPlansCount++;
        }
    }

    private Plan createNewPlan(Person person) {
        Plan oldPlan = person.getSelectedPlan();
        Plan newPlan = PopulationUtils.createPlan(person);
        
        Activity previousActivity = null;
        
        for (PlanElement pe : oldPlan.getPlanElements()) {
            if (pe instanceof Activity) {
                Activity activity = (Activity) pe;
                newPlan.addActivity(activity);
                previousActivity = activity;
            } else if (pe instanceof Leg) {
                Leg oldLeg = (Leg) pe;
                if (oldLeg.getMode().equals("car") && oldLeg.getRoute() instanceof NetworkRoute) {
                    String vehicleId = (String) oldLeg.getAttributes().getAttribute("vehicleId");
                    ZeroEmissionZone.VehicleCategory category = 
                        ZeroEmissionZone.VehicleCategory.fromVehicleType(vehicleId);
                    
                    if (previousActivity != null) {
                        LocalTime departureTime = extractDepartureTime(previousActivity);
                        
                        NetworkRoute newRoute = generateCategorySpecificRoute(
                            (NetworkRoute) oldLeg.getRoute(),
                            category,
                            departureTime
                        );
                        
                        if (newRoute != null) {
                            Leg newLeg = PopulationUtils.createLeg(oldLeg.getMode());
                            newLeg.setRoute(newRoute);
                            newLeg.getAttributes().putAttribute("vehicleId", vehicleId);
                            newPlan.addLeg(newLeg);
                            continue;
                        }
                    }
                }
                newPlan.addLeg(oldLeg);
            }
        }
        
        return newPlan;
    }

    private LocalTime extractDepartureTime(Activity activity) {
        double timeInSeconds = activity.getEndTime().seconds();
        int hours = (int) (timeInSeconds / 3600) % 24;
        int minutes = (int) ((timeInSeconds % 3600) / 60);
        return LocalTime.of(hours, minutes);
    }

    private NetworkRoute generateCategorySpecificRoute(
        NetworkRoute oldRoute,
        ZeroEmissionZone.VehicleCategory category,
        LocalTime departureTime
    ) {
        Node startNode = network.getLinks().get(oldRoute.getStartLinkId()).getFromNode();
        Node endNode = network.getLinks().get(oldRoute.getEndLinkId()).getToNode();

        List<Link> path = findCategorySpecificPath(startNode, endNode, category, departureTime);
        
        if (path.isEmpty()) {
            logger.warn("No valid route found for {} vehicle", category);
            return null;
        }

        List<Id<Link>> linkIds = path.stream()
            .map(Link::getId)
            .collect(Collectors.toList());

        NetworkRoute route = RouteUtils.createLinkNetworkRouteImpl(oldRoute.getStartLinkId(), oldRoute.getEndLinkId());
        route.setLinkIds(oldRoute.getStartLinkId(), linkIds, oldRoute.getEndLinkId());

        return route;
    }

    private List<Link> findCategorySpecificPath(
        Node start,
        Node end,
        ZeroEmissionZone.VehicleCategory category,
        LocalTime departureTime
    ) {
        Map<Node, Double> distances = new HashMap<>();
        Map<Node, Node> previousNodes = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(
            Comparator.comparingDouble(n -> distances.getOrDefault(n, Double.POSITIVE_INFINITY))
        );
        Set<Node> visited = new HashSet<>();

        distances.put(start, 0.0);
        queue.add(start);

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            if (current.equals(end)) break;
            if (visited.contains(current)) continue;

            visited.add(current);

            for (Link link : current.getOutLinks().values()) {
                Node neighbor = link.getToNode();
                
                // Strict access control for HEVs during operating hours
                if (zeroEmissionZone.isZoneLink(link.getId()) && 
                    category == ZeroEmissionZone.VehicleCategory.HEAVY_EMISSION && 
                    zeroEmissionZone.isOperatingHours(departureTime)) {
                    continue;
                }

                double linkCost = linkCosts.get(link).get(category);
                double newDist = distances.get(current) + linkCost;

                if (newDist < distances.getOrDefault(neighbor, Double.POSITIVE_INFINITY)) {
                    distances.put(neighbor, newDist);
                    previousNodes.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }

        return reconstructPath(start, end, previousNodes);
    }

    private List<Link> reconstructPath(Node start, Node end, Map<Node, Node> previousNodes) {
        List<Link> path = new ArrayList<>();
        Node currentNode = end;
        
        while (currentNode != null && !currentNode.equals(start)) {
            final Node targetNode = currentNode;
            Node previousNode = previousNodes.get(currentNode);
            
            if (previousNode == null) break;

            Link link = previousNode.getOutLinks().values().stream()
                .filter(l -> l.getToNode().equals(targetNode))
                .findFirst()
                .orElse(null);

            if (link != null) {
                path.add(0, link);
            }
            
            currentNode = previousNode;
        }

        return path;
    }

    public void reset() {
        reroutedPlansCount = 0;
        logger.info("Reset ReroutingStrategy");
    }

    public int getReroutedPlansCount() {
        return reroutedPlansCount;
    }

    public ZeroEmissionZone getZeroEmissionZone() {
        return zeroEmissionZone;
    }
}
