package ez.backend.turbo.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.output.OutputManager;
import ez.backend.turbo.session.SseEmitterRegistry;
import ez.backend.turbo.simulation.MatsimRunner;
import ez.backend.turbo.simulation.MatsimRunner.SimulationResult;
import ez.backend.turbo.simulation.ZoneEnforcementModule;
import ez.backend.turbo.simulation.ZoneLinkResolver;
import ez.backend.turbo.simulation.ZonePolicyIndex;
import ez.backend.turbo.simulation.ZoneLinkResolver.ZoneLinkSet;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ScenarioStatus;
import ez.backend.turbo.validation.SimulationRequestValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicles;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class SimulationService {

    private static final Logger log = LogManager.getLogger(SimulationService.class);

    private final ScenarioStateService scenarioStateService;
    private final ProcessManager processManager;
    private final SseMessageSender messageSender;
    private final SseEmitterRegistry emitterRegistry;
    private final ObjectMapper objectMapper;
    private final SimulationRequestValidator validator;
    private final PopulationFilterService populationFilterService;
    private final PopulationReconstructionService populationReconstructionService;
    private final VehicleAssignmentService vehicleAssignmentService;
    private final ZoneLinkResolver zoneLinkResolver;
    private final MatsimRunner matsimRunner;
    private final SourceRegistry sourceRegistry;
    private final OutputManager outputManager;
    @Nullable private final SimulationQueueManager queueManager;

    public SimulationService(ScenarioStateService scenarioStateService,
                             ProcessManager processManager,
                             SseMessageSender messageSender,
                             SseEmitterRegistry emitterRegistry,
                             ObjectMapper objectMapper,
                             SimulationRequestValidator validator,
                             PopulationFilterService populationFilterService,
                             PopulationReconstructionService populationReconstructionService,
                             VehicleAssignmentService vehicleAssignmentService,
                             ZoneLinkResolver zoneLinkResolver,
                             MatsimRunner matsimRunner,
                             SourceRegistry sourceRegistry,
                             OutputManager outputManager,
                             @Nullable SimulationQueueManager queueManager) {
        this.scenarioStateService = scenarioStateService;
        this.processManager = processManager;
        this.messageSender = messageSender;
        this.emitterRegistry = emitterRegistry;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.populationFilterService = populationFilterService;
        this.populationReconstructionService = populationReconstructionService;
        this.vehicleAssignmentService = vehicleAssignmentService;
        this.zoneLinkResolver = zoneLinkResolver;
        this.matsimRunner = matsimRunner;
        this.sourceRegistry = sourceRegistry;
        this.outputManager = outputManager;
        this.queueManager = queueManager;
    }

    public SseEmitter startSimulation(SimulationRequest request) {
        validator.validate(request);
        UUID requestId = scenarioStateService.createScenario();

        if (queueManager != null) {
            return startQueued(requestId, request);
        }
        return startDirect(requestId, request);
    }

    private SseEmitter startDirect(UUID requestId, SimulationRequest request) {
        boolean registered = processManager.register(requestId, ProcessType.COMPUTE, "/simulate");
        if (!registered) {
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            throw new IllegalStateException(L.msg("simulation.rejected"));
        }

        try {
            long timeout = processManager.getTimeout(ProcessType.COMPUTE);
            SseEmitter emitter = new SseEmitter(timeout);
            emitterRegistry.register(requestId, emitter);

            storeInput(requestId, request);

            CompletableFuture.runAsync(() -> runDirectPipeline(requestId, emitter, request));

            return emitter;
        } catch (Exception e) {
            emitterRegistry.remove(requestId);
            processManager.unregister(requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            throw e;
        }
    }

    private SseEmitter startQueued(UUID requestId, SimulationRequest request) {
        long computeTimeout = processManager.getTimeout(ProcessType.COMPUTE);
        long totalTimeout = computeTimeout + queueManager.getQueueTimeout();
        SseEmitter emitter = new SseEmitter(totalTimeout);
        emitterRegistry.register(requestId, emitter);

        try {
            storeInput(requestId, request);
        } catch (Exception e) {
            emitterRegistry.remove(requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            throw e;
        }

        boolean idle = queueManager.hasIdleWorker();
        boolean submitted = queueManager.submit(requestId, emitter, !idle, request);
        if (!submitted) {
            emitterRegistry.remove(requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            throw new IllegalStateException(L.msg("simulation.queue.full"));
        }

        if (!idle) {
            scenarioStateService.updateStatus(requestId, ScenarioStatus.QUEUED);
            log.info(L.msg("simulation.queued"));
        }

        return emitter;
    }

    void executePipeline(UUID requestId, SseEmitter emitter, SimulationRequest request) {
        ThreadContext.put("ctx", requestId.toString());
        try {
            log.info(L.msg("simulation.started"));
            messageSender.sendLifecycle(emitter, MessageType.PA_SIMULATION_START);

            int netYear = request.getSources().getNetwork().getYear();
            String netName = request.getSources().getNetwork().getName();
            int popYear = request.getSources().getPopulation().getYear();
            String popName = request.getSources().getPopulation().getName();
            int percentage = request.getSimulationOptions().getPercentage();

            checkCancelled(requestId);

            List<ZoneLinkSet> zoneLinkSets = zoneLinkResolver.resolve(
                    request.getZones(), netYear, netName);
            Set<String> filteredPersonIds = populationFilterService.filter(request, zoneLinkSets);
            Path plansFile = populationReconstructionService.reconstructAndSample(
                    filteredPersonIds, popYear, popName, requestId, percentage);
            log.info(L.msg("simulation.population.ready"));

            Population population = PopulationUtils.readPopulation(plansFile.toString());
            int personCount = population.getPersons().size();
            Vehicles vehicles = vehicleAssignmentService.assign(population, request.getCarDistribution());
            Path vehiclesFile = plansFile.getParent().resolve("vehicles.xml");
            new MatsimVehicleWriter(vehicles).writeFile(vehiclesFile.toString());
            new PopulationWriter(population).write(plansFile.toString());

            Network network = sourceRegistry.getNetwork(netYear, netName);
            int networkNodes = network.getNodes().size();
            int networkLinks = network.getLinks().size();
            double simulationAreaKm2 = zoneLinkResolver.computeTotalAreaKm2(zoneLinkSets);

            checkCancelled(requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.SIMULATING_BASELINE);
            log.info(L.msg("simulation.stage.baseline"));
            SimulationResult baselineResult = matsimRunner.runSimulation(
                    request, requestId, population, vehicles, plansFile, vehiclesFile, "baseline",
                    processManager);

            checkCancelled(requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.SIMULATING_POLICY);
            log.info(L.msg("simulation.stage.policy"));
            ZonePolicyIndex policyIndex = ZonePolicyIndex.build(request.getZones(), zoneLinkSets);
            ZoneEnforcementModule enforcementModule = new ZoneEnforcementModule(policyIndex);
            Population policyPopulation = PopulationUtils.readPopulation(plansFile.toString());
            SimulationResult policyResult = matsimRunner.runSimulation(
                    request, requestId, policyPopulation, vehicles,
                    plansFile, vehiclesFile, "policy", processManager, enforcementModule);

            checkCancelled(requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.POSTPROCESSING);
            log.info(L.msg("simulation.stage.postprocess"));

            outputManager.processOutput(requestId, emitter, request, vehicles,
                    personCount, networkNodes, networkLinks, simulationAreaKm2,
                    baselineResult, policyResult);
            log.info(L.msg("simulation.completed"));

        } catch (CancellationException e) {
            handleCancellation(requestId, emitter);
        } catch (Exception e) {
            if (processManager.isCancelled(requestId)) {
                handleCancellation(requestId, emitter);
                return;
            }
            log.error("{}: {}", L.msg("simulation.failed"), e.getMessage(), e);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL, "PIPELINE_ERROR", e.getMessage());
            messageSender.complete(emitter);
        } finally {
            ThreadContext.remove("ctx");
        }
    }

    private void checkCancelled(UUID requestId) {
        if (processManager.isCancelled(requestId)) {
            throw new CancellationException(requestId);
        }
    }

    private void handleCancellation(UUID requestId, SseEmitter emitter) {
        log.info("{}: {}", L.msg("scenario.cancel.confirmed"), requestId);
        ScenarioStatus current = scenarioStateService.getStatus(requestId).orElse(null);
        if (current != ScenarioStatus.DELETED) {
            scenarioStateService.updateStatus(requestId, ScenarioStatus.CANCELLED);
        }
        messageSender.sendMessage(emitter, MessageType.CANCELLED_PROCESS,
                Map.of("requestId", requestId.toString()));
        messageSender.complete(emitter);
        scenarioStateService.cleanupOutputData(requestId);
    }

    static class CancellationException extends RuntimeException {
        CancellationException(UUID requestId) {
            super(requestId.toString());
        }
    }

    private void runDirectPipeline(UUID requestId, SseEmitter emitter, SimulationRequest request) {
        try {
            messageSender.sendMessage(emitter, MessageType.PA_REQUEST_ACCEPTED,
                    Map.of("requestId", requestId.toString()));
            executePipeline(requestId, emitter, request);
        } catch (Exception e) {
            log.error("{}: {}", L.msg("simulation.failed"), e.getMessage(), e);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.FAILED);
            messageSender.sendError(emitter, MessageType.ERROR_GLOBAL, "PIPELINE_ERROR", e.getMessage());
            messageSender.complete(emitter);
        } finally {
            processManager.unregister(requestId);
        }
    }

    private void storeInput(UUID requestId, SimulationRequest request) {
        try {
            String inputJson = objectMapper.writeValueAsString(request);
            scenarioStateService.storeInputData(requestId, inputJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
