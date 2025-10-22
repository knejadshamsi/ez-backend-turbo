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

import java.util.*;
import java.util.stream.Collectors;

public class ReroutingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ReroutingStrategy.class);
    private static final double EMISSION_ZONE_COST_MULTIPLIER = 2.0;
    
    private final Network network;
    private final double scoreThreshold;
    private final Map<Link, Double> linkCosts = new HashMap<>();
    private final ZeroEmissionZone zeroEmissionZone;
    
    private int reroutedPlansCount = 0;

    public ReroutingStrategy(Network network, double scoreThreshold) {
        this.network = network;
        this.scoreThreshold = scoreThreshold;
        ZeroEmissionZoneConfigGroup configGroup = new ZeroEmissionZoneConfigGroup();
        this.zeroEmissionZone = new ZeroEmissionZone(network, configGroup);
        initializeLinkCosts();
        logger.info("Initialized ReroutingStrategy with score threshold: {}", scoreThreshold);
    }

    private void initializeLinkCosts() {
        for (Link link : network.getLinks().values()) {
            // Basic cost based on travel time
            double baseCost = link.getLength() / link.getFreespeed();
            
            // Apply penalty for zero emission zone links
            if (zeroEmissionZone.isInZeroEmissionZone(link.getId())) {
                baseCost *= EMISSION_ZONE_COST_MULTIPLIER;
            }
            
            linkCosts.put(link, baseCost);
        }
    }

    public void processAgent(Person person) {
        Plan selectedPlan = person.getSelectedPlan();
        
        if (selectedPlan == null || selectedPlan.getScore() == null) {
            return;
        }
        
        // Only reroute if the score is below threshold
        if (selectedPlan.getScore() < scoreThreshold) {
            Plan newPlan = createNewPlan(person);
            if (newPlan != null) {
                person.addPlan(newPlan);
                person.setSelectedPlan(newPlan);
                reroutedPlansCount++;
                logger.info("Rerouted plan for person {} with score {}", 
                    person.getId(), selectedPlan.getScore());
            }
        }
    }

    private Plan createNewPlan(Person person) {
        Plan oldPlan = person.getSelectedPlan();
        Plan newPlan = PopulationUtils.createPlan(person);
        
        for (PlanElement pe : oldPlan.getPlanElements()) {
            if (pe instanceof Activity) {
                newPlan.addActivity((Activity) pe);
            } else if (pe instanceof Leg) {
                Leg oldLeg = (Leg) pe;
                if (oldLeg.getMode().equals("car") && oldLeg.getRoute() instanceof NetworkRoute) {
                    NetworkRoute oldRoute = (NetworkRoute) oldLeg.getRoute();
                    NetworkRoute newRoute = generateAlternativeRoute(oldRoute);
                    
                    if (newRoute != null && validateRoute(newRoute)) {
                        Leg newLeg = PopulationUtils.createLeg(oldLeg.getMode());
                        newLeg.setRoute(newRoute);
                        newPlan.addLeg(newLeg);
                    } else {
                        newPlan.addLeg(oldLeg); // Keep original leg if new route is invalid
                    }
                } else {
                    newPlan.addLeg(oldLeg);
                }
            }
        }
        
        return newPlan;
    }

    private NetworkRoute generateAlternativeRoute(NetworkRoute oldRoute) {
        Node startNode = network.getLinks().get(oldRoute.getStartLinkId()).getFromNode();
        Node endNode = network.getLinks().get(oldRoute.getEndLinkId()).getToNode();

        List<Link> path = findShortestPath(startNode, endNode);
        
        if (path.isEmpty()) {
            return null;
        }

        List<Id<Link>> linkIds = path.stream()
            .map(Link::getId)
            .collect(Collectors.toList());

        NetworkRoute route = RouteUtils.createNetworkRoute(linkIds, network);
        route.setStartLinkId(oldRoute.getStartLinkId());
        route.setEndLinkId(oldRoute.getEndLinkId());

        return route;
    }

    private List<Link> findShortestPath(Node start, Node end) {
        Map<Node, Double> distances = new HashMap<>();
        Map<Node, Node> previousNodes = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(
            Comparator.comparingDouble(n -> distances.getOrDefault(n, Double.POSITIVE_INFINITY)));
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
                double linkCost = linkCosts.get(link);
                
                // Add additional cost for zero emission zone links using the constant penalty
                if (zeroEmissionZone.isInZeroEmissionZone(link.getId())) {
                    linkCost += 50.0; // Using the ZERO_EMISSION_ZONE_PENALTY value directly
                }
                
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

    private boolean validateRoute(NetworkRoute route) {
        List<Link> routeLinks = route.getLinkIds().stream()
            .map(network.getLinks()::get)
            .collect(Collectors.toList());

        if (routeLinks.isEmpty()) {
            return false;
        }

        // Check consecutive link connectivity
        for (int i = 0; i < routeLinks.size() - 1; i++) {
            Link currentLink = routeLinks.get(i);
            Link nextLink = routeLinks.get(i + 1);

            Node fromLinkToNode = currentLink.getToNode();
            Node toLinkFromNode = nextLink.getFromNode();

            if (!fromLinkToNode.equals(toLinkFromNode)) {
                return false;
            }
        }

        // Check if route avoids zero emission zones when possible
        int zezLinkCount = (int) routeLinks.stream()
            .filter(link -> zeroEmissionZone.isInZeroEmissionZone(link.getId()))
            .count();

        // If more than half the links are in zero emission zones, try to find a better route
        return zezLinkCount <= routeLinks.size() / 2;
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
