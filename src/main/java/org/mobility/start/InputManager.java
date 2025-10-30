package org.mobility.start;

import org.matsim.core.config.Config;
import org.mobility.manager.StateManager;
import org.mobility.start.constructor.ConfigConstructor;
import org.mobility.start.constructor.NetworkConstructor;
import org.mobility.start.constructor.PopulationConstructor;
import org.mobility.start.sim.SimulationRunner;
import org.mobility.status.StatusManager;
import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class InputManager {
    private final ConfigConstructor configConstructor;
    private final NetworkConstructor networkConstructor;
    private final PopulationConstructor populationConstructor;
    private final SimulationRunner simulationRunner;
    private final StatusManager statusManager;
    private final StateManager stateManager;

    @Autowired
    public InputManager(
        ConfigConstructor configConstructor,
        NetworkConstructor networkConstructor,
        PopulationConstructor populationConstructor,
        SimulationRunner simulationRunner,
        StatusManager statusManager,
        StateManager stateManager
    ) {
        this.configConstructor = configConstructor;
        this.networkConstructor = networkConstructor;
        this.populationConstructor = populationConstructor;
        this.simulationRunner = simulationRunner;
        this.statusManager = statusManager;
        this.stateManager = stateManager;
    }

    public WorkflowResult processInput(Map<String, Object> request) {
        String requestId = (String) request.get("requestId");

        if (stateManager.isCancellationRequested(requestId)) {
            return WorkflowResult.error(503, "Simulation cancelled");
        }

        try {
            statusManager.updateStatus(requestId, StatusManager.STATUS_PREPARING_INPUT, "Preparing input");

            Config config = configConstructor.constructConfig(request);
            config.network().setInputFile(null);
            config.plans().setInputFile(null);
            config.vehicles().setVehiclesFile(null);

            Map<String, Object> inputData = new HashMap<>(request);
            inputData.put("config", config);

            if (request.containsKey("zoneLinks")) {
                List<?> zoneLinks = (List<?>) request.get("zoneLinks");
                List<String> normalizedZoneLinks = zoneLinks.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .collect(Collectors.toList());
                inputData.put("zoneLinks", normalizedZoneLinks);
            }

            inputData.put("network", networkConstructor.constructNetwork(inputData));

            Map<String, Object> settings = (Map<String, Object>) request.get("settings");
            boolean simulateAllAgents = settings != null && 
                settings.containsKey("simulateAllAgents") && 
                (Boolean) settings.get("simulateAllAgents");

            Map<String, Object> populationRequest = new HashMap<>(inputData);
            populationRequest.put("simulateAllAgents", simulateAllAgents);
            inputData.put("population", populationConstructor.constructPopulation(populationRequest));

            return WorkflowResult.success(inputData);
        } catch (Exception e) {
            statusManager.updateStatus(requestId, StatusManager.STATUS_FAILED, "Input processing failed: " + e.getMessage());
            return WorkflowResult.error(500, "Error processing input: " + e.getMessage());
        }
    }
}
