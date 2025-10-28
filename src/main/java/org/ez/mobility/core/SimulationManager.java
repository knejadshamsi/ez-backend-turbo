package org.ez.mobility.core;

import org.ez.mobility.api.SimulationRequest;
import org.ez.mobility.api.SimulationStatus;
import org.ez.mobility.api.SimulationStatus.SimulationState;
import org.ez.mobility.ez.ZeroEmissionZoneConfigGroup;
import org.ez.mobility.ez.TransitConfigGroup;
import org.ez.mobility.output.OutputEvent;
import org.ez.mobility.output.OutputEventRepository;
import org.ez.mobility.ez.StatisticsCollector;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.events.handler.BasicEventHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;

@Component
public class SimulationManager {
    private static final Logger logger = LoggerFactory.getLogger(SimulationManager.class);
    
    private final SimulationStatusRepository statusRepository;
    private final InputManager inputManager;
    private final OutputManager outputManager;
    private final OutputEventRepository eventRepository;
    private final StatisticsCollector statisticsCollector;
    private final Map<String, CompletableFuture<Void>> runningSimulations;

    @Value("${simulation.transit.maxWalkDistance:1000.0}")
    private double maxWalkDistance;

    @Value("${simulation.transit.searchRadius:1000.0}")
    private double searchRadius;

    @Value("${simulation.transit.extensionRadius:500.0}")
    private double extensionRadius;

    @Value("${simulation.transit.ptConstant:-1.0}")
    private double ptConstant;

    @Value("${simulation.transit.ptUtility:-6.0}")
    private double ptUtility;

    @Value("${simulation.transit.walkConstant:-3.0}")
    private double walkConstant;

    @Value("${simulation.transit.walkUtility:-12.0}")
    private double walkUtility;

    public SimulationManager(
            SimulationStatusRepository statusRepository,
            InputManager inputManager,
            OutputManager outputManager,
            OutputEventRepository eventRepository,
            StatisticsCollector statisticsCollector) {
        this.statusRepository = statusRepository;
        this.inputManager = inputManager;
        this.outputManager = outputManager;
        this.eventRepository = eventRepository;
        this.statisticsCollector = statisticsCollector;
        this.runningSimulations = new ConcurrentHashMap<>();
    }

    @Transactional
    public boolean runSimulation(String requestId, SimulationRequest request) {
        if (statusRepository.existsByRequestId(requestId)) {
            logger.error("Duplicate request ID: {}", requestId);
            return false;
        }

        CompletableFuture<Void> simulationFuture = CompletableFuture.runAsync(() -> {
            try {
                updateStatus(requestId, SimulationState.RECEIVED);
                
                updateStatus(requestId, SimulationState.PREPARING);
                inputManager.prepareSimulation(requestId);
                inputManager.validateSimulationFiles(requestId);
                
                updateStatus(requestId, SimulationState.RUNNING);
                runMatsimSimulation(requestId);
                
                updateStatus(requestId, SimulationState.PROCESSING);
                outputManager.processOutputs(requestId);
                
                updateStatus(requestId, SimulationState.STORING);
                outputManager.storeResults(requestId);
                statisticsCollector.persistAndCleanup(requestId);
                
                updateStatus(requestId, SimulationState.COMPLETED);
                
            } catch (Exception e) {
                logger.error("Simulation failed for request ID: " + requestId, e);
                handleSimulationError(requestId, e);
            } finally {
                runningSimulations.remove(requestId);
            }
        });

        runningSimulations.put(requestId, simulationFuture);
        return true;
    }

    public Map<String, Object> getSimulationStatus(String requestId) {
        Optional<SimulationStatus> status = statusRepository.findById(requestId);
        if (!status.isPresent()) {
            return null;
        }

        Map<String, Object> statusInfo = new HashMap<>();
        statusInfo.put("state", status.get().getState());
        statusInfo.put("errorMessage", status.get().getErrorMessage());

        if (status.get().getState() == SimulationState.COMPLETED) {
            statusInfo.put("statistics", statisticsCollector.generateReport(requestId));
            statusInfo.put("eventCount", eventRepository.countByRequestId(requestId));
        }

        return statusInfo;
    }

    public boolean cancelSimulation(String requestId) {
        CompletableFuture<Void> simulation = runningSimulations.get(requestId);
        if (simulation == null) {
            return false;
        }

        simulation.cancel(true);
        runningSimulations.remove(requestId);
        
        SimulationStatus status = statusRepository.findById(requestId)
            .orElseGet(() -> {
                SimulationStatus newStatus = new SimulationStatus();
                newStatus.setRequestId(requestId);
                return newStatus;
            });
        
        status.setState(SimulationState.CANCELLED);
        statusRepository.save(status);
        
        logger.info("Simulation cancelled: {}", requestId);
        return true;
    }

    private void runMatsimSimulation(String requestId) {
        Path configPath = Paths.get("simulations", requestId, "config.xml");
        Config config = ConfigUtils.loadConfig(configPath.toString());
        
        // Configure transit settings
        configureTransit(config);
        
        Controler controler = new Controler(config);
        DatabaseEventHandler eventHandler = new DatabaseEventHandler(requestId, eventRepository);
        controler.getEvents().addHandler(eventHandler);
        
        controler.run();
    }

    private void configureTransit(Config config) {
        // Configure QSim for transit
        config.qsim().setMainModes(Arrays.asList("car", "pt"));
        config.qsim().setVehicleBehavior(QSimConfigGroup.VehicleBehavior.wait);

        // Configure transit router
        config.transitRouter().setMaxBeelineWalkConnectionDistance(maxWalkDistance);
        config.transitRouter().setSearchRadius(searchRadius);
        config.transitRouter().setExtensionRadius(extensionRadius);

        // Configure planCalcScore for transit and walk modes
        config.planCalcScore().getModes().get("pt").setConstant(ptConstant);
        config.planCalcScore().getModes().get("pt").setMarginalUtilityOfTraveling(ptUtility);
        config.planCalcScore().getModes().get("walk").setConstant(walkConstant);
        config.planCalcScore().getModes().get("walk").setMarginalUtilityOfTraveling(walkUtility);
    }

    private void updateStatus(String requestId, SimulationState state) {
        SimulationStatus status = statusRepository.findById(requestId)
                .orElseGet(() -> {
                    SimulationStatus newStatus = new SimulationStatus();
                    newStatus.setRequestId(requestId);
                    return newStatus;
                });
        
        status.setState(state);
        statusRepository.save(status);
        logger.info("Updated simulation status for request {}: {}", requestId, state);
    }

    private void handleSimulationError(String requestId, Exception e) {
        logger.error("Simulation failed for request {}: {}", requestId, e.getMessage(), e);
        
        SimulationState currentState = statusRepository.findById(requestId)
                .map(SimulationStatus::getState)
                .orElse(SimulationState.RECEIVED);
                
        SimulationState failedState;
        switch (currentState) {
            case PREPARING:
                failedState = SimulationState.FAILED_PREPARING;
                break;
            case RUNNING:
                failedState = SimulationState.FAILED_RUNNING;
                break;
            case PROCESSING:
                failedState = SimulationState.FAILED_PROCESSING;
                break;
            case STORING:
                failedState = SimulationState.FAILED_STORING;
                break;
            default:
                failedState = SimulationState.FAILED_PREPARING;
                break;
        }

        SimulationStatus status = statusRepository.findById(requestId)
                .orElseGet(() -> {
                    SimulationStatus newStatus = new SimulationStatus();
                    newStatus.setRequestId(requestId);
                    return newStatus;
                });
        
        status.setState(failedState);
        status.setErrorMessage(e.getMessage());
        statusRepository.save(status);
    }

    private static class DatabaseEventHandler implements BasicEventHandler {
        private final String requestId;
        private final OutputEventRepository eventRepository;
        private static final int BATCH_SIZE = 1000;
        private int eventCount = 0;

        public DatabaseEventHandler(String requestId, OutputEventRepository eventRepository) {
            this.requestId = requestId;
            this.eventRepository = eventRepository;
        }

        @Override
        public void handleEvent(Event event) {
            OutputEvent outputEvent = new OutputEvent();
            outputEvent.setRequestId(requestId);
            outputEvent.setEventTime(event.getTime());
            outputEvent.setEventType(event.getEventType());
            outputEvent.setAttributes(event.getAttributes());
            
            eventRepository.save(outputEvent);
            
            eventCount++;
            if (eventCount % BATCH_SIZE == 0) {
                eventRepository.flush();
            }
        }

        @Override
        public void reset(int iteration) {
            // No need to clear events as they're stored in the database
        }
    }
}
