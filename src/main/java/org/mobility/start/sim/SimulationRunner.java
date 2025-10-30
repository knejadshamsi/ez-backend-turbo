package org.mobility.start.sim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.mobsim.qsim.QSim;
import org.mobility.manager.StateManager;
import org.mobility.status.StatusManager;
import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

@Component
public class SimulationRunner {
    private final EmissionZoneReplannerFactory replannerFactory;
    private final EmissionZoneHandler emissionZoneHandler;
    private final EmissionZonePenaltyCalculator penaltyCalculator;
    private final StuckAgentHandler stuckAgentHandler;
    private final StateManager stateManager;
    private final StatusManager statusManager;

    @Autowired
    public SimulationRunner(
        EmissionZoneReplannerFactory replannerFactory,
        EmissionZoneHandler emissionZoneHandler,
        EmissionZonePenaltyCalculator penaltyCalculator,
        StuckAgentHandler stuckAgentHandler,
        StateManager stateManager,
        StatusManager statusManager
    ) {
        this.replannerFactory = replannerFactory;
        this.emissionZoneHandler = emissionZoneHandler;
        this.penaltyCalculator = penaltyCalculator;
        this.stuckAgentHandler = stuckAgentHandler;
        this.stateManager = stateManager;
        this.statusManager = statusManager;
    }

    public WorkflowResult runSimulation(Map<String, Object> input) {
        String requestId = (String) input.get("requestId");

        if (stateManager.isCancellationRequested(requestId)) {
            return WorkflowResult.error(503, "Simulation cancelled");
        }

        try {
            statusManager.updateStatus(requestId, StatusManager.STATUS_RUNNING, "Simulation in progress");

            Config config = (Config) input.get("config");
            Network network = (Network) input.get("network");
            Population population = (Population) input.get("population");
            
            config.controler().setCreateGraphs(false);
            config.controler().setWriteEventsInterval(0);
            config.controler().setWritePlansInterval(0);
            config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

            Scenario scenario = ScenarioUtils.loadScenario(config);
            
            for (Map.Entry<Id<Node>, ? extends Node> entry : network.getNodes().entrySet()) {
                scenario.getNetwork().addNode(entry.getValue());
            }
            
            for (Map.Entry<Id<Link>, ? extends Link> entry : network.getLinks().entrySet()) {
                scenario.getNetwork().addLink(entry.getValue());
            }
            
            for (Map.Entry<Id<Person>, ? extends Person> entry : population.getPersons().entrySet()) {
                scenario.getPopulation().addPerson(entry.getValue());
            }

            Controler controler = new Controler(scenario);
            
            setupEmissionZone(input);
            stuckAgentHandler.setRequestId(requestId);
            
            controler.getEvents().addHandler(emissionZoneHandler);
            controler.getEvents().addHandler(stuckAgentHandler);
            controler.addControlerListener(penaltyCalculator);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                QSim qsim = (QSim) controler.getScenario().getScenarioElement("qsim");
                if (qsim != null) {
                    emissionZoneHandler.setQSim(qsim);
                    replannerFactory.initialize(qsim, controler.getTripRouterProvider().get());
                    stateManager.registerSimulation(requestId, qsim, CompletableFuture.completedFuture(null));
                }
                controler.run();
            });

            future.join();

            if (stateManager.isCancellationRequested(requestId)) {
                return WorkflowResult.error(503, "Simulation cancelled");
            }

            Map<String, Object> simulationResults = new HashMap<>(input);
            simulationResults.put("controler", controler);

            return WorkflowResult.success(simulationResults);
        } catch (Exception e) {
            statusManager.updateStatus(requestId, StatusManager.STATUS_FAILED, "Simulation failed: " + e.getMessage());
            return WorkflowResult.error(500, "Simulation error: " + e.getMessage());
        } finally {
            stateManager.removeState(requestId);
        }
    }

    private void setupEmissionZone(Map<String, Object> input) {
        if (input.containsKey("zoneLinks")) {
            List<String> zoneLinks = (List<String>) input.get("zoneLinks");
            for (String linkId : zoneLinks) {
                emissionZoneHandler.addZoneLink(linkId);
            }
        }

        if (input.containsKey("policy")) {
            List<Map<String, Object>> policies = (List<Map<String, Object>>) input.get("policy");
            for (Map<String, Object> policy : policies) {
                String vehicleType = (String) policy.get("vehicleType");
                Object policyValues = policy.get("policyValues");
                List<String> operatingHours = (List<String>) policy.get("operatingHours");

                String policyValue;
                if (policyValues instanceof String) {
                    policyValue = (String) policyValues;
                } else if (policyValues instanceof List) {
                    List<Object> values = (List<Object>) policyValues;
                    policyValue = String.join(",", values.stream().map(String::valueOf).toArray(String[]::new));
                } else {
                    continue;
                }

                emissionZoneHandler.addPolicy(
                    vehicleType,
                    policyValue,
                    operatingHours.toArray(new String[0])
                );
            }
        }
    }
}
