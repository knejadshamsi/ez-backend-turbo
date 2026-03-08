package ez.backend.turbo.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.output.OutputManager;
import ez.backend.turbo.session.SseEmitterRegistry;
import ez.backend.turbo.simulation.MatsimConfigBuilder;
import ez.backend.turbo.simulation.MatsimRunner;
import ez.backend.turbo.simulation.MatsimRunner.SimulationResult;
import ez.backend.turbo.simulation.TransitPreparer;
import ez.backend.turbo.simulation.ZoneEnforcementModule;
import ez.backend.turbo.simulation.ZoneLinkResolver;
import ez.backend.turbo.simulation.ZonePolicyIndex;
import ez.backend.turbo.simulation.ZoneLinkResolver.ZoneLinkSet;
import ez.backend.turbo.sse.SseMessageSender;
import ez.backend.turbo.utils.CancellationException;
import ez.backend.turbo.utils.L;
import ez.backend.turbo.utils.MessageType;
import ez.backend.turbo.utils.ScenarioStatus;
import ez.backend.turbo.validation.SimulationRequestValidator;
import ez.backend.turbo.validation.ValidationResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

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
    private final MatsimConfigBuilder configBuilder;
    private final TransitPreparer transitPreparer;
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
                             MatsimConfigBuilder configBuilder,
                             TransitPreparer transitPreparer,
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
        this.configBuilder = configBuilder;
        this.transitPreparer = transitPreparer;
        this.sourceRegistry = sourceRegistry;
        this.outputManager = outputManager;
        this.queueManager = queueManager;
    }

    public SseEmitter startSimulation(SimulationRequest request) {
        ValidationResult validation = validator.validate(request);
        if (validation.hasErrors()) {
            SseEmitter emitter = new SseEmitter(5000L);
            messageSender.sendValidationErrors(emitter, validation.errors());
            messageSender.complete(emitter);
            return emitter;
        }
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
            int transitYear = request.getSources().getPublicTransport().getYear();
            String transitName = request.getSources().getPublicTransport().getName();
            int percentage = request.getSimulationOptions().getPercentage();

            checkCancelled(requestId);

            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_NETWORK_STARTED);
            processManager.setProgress(requestId, L.msg("simulation.progress.network"));
            Network network = sourceRegistry.getNetwork(netYear, netName);
            int networkNodes = network.getNodes().size();
            int networkLinks = network.getLinks().size();
            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_NETWORK_COMPLETE);
            checkCancelled(requestId);

            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_POPULATION_STARTED);
            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_TRANSIT_STARTED);

            final Network sharedNetwork = network;
            CompletableFuture<PopulationPrepResult> populationFuture = CompletableFuture.supplyAsync(() -> {
                ThreadContext.put("ctx", requestId.toString());
                try {
                    processManager.setProgress(requestId, L.msg("simulation.progress.zones"));
                    List<ZoneLinkSet> zls = zoneLinkResolver.resolve(
                            request.getZones(), netYear, netName);
                    checkCancelled(requestId);

                    processManager.setProgress(requestId, L.msg("simulation.progress.population"));
                    Set<String> filteredIds = populationFilterService.filter(request, zls);
                    checkCancelled(requestId);

                    processManager.setProgress(requestId, L.msg("simulation.progress.reconstruction"));
                    Path plans = populationReconstructionService.reconstructAndSample(
                            filteredIds, popYear, popName, requestId, percentage);
                    log.info(L.msg("simulation.population.ready"));
                    checkCancelled(requestId);

                    processManager.setProgress(requestId, L.msg("simulation.progress.vehicles"));
                    Population pop = PopulationUtils.readPopulation(plans.toString());
                    Vehicles vehs = vehicleAssignmentService.assign(pop, request.getCarDistribution());
                    Path vehFile = plans.getParent().resolve("vehicles.xml");
                    new MatsimVehicleWriter(vehs).writeFile(vehFile.toString());
                    new PopulationWriter(pop).write(plans.toString());

                    double area = zoneLinkResolver.computeTotalAreaKm2(zls);
                    return new PopulationPrepResult(pop, vehs, plans, vehFile,
                            pop.getPersons().size(), area, zls);
                } finally {
                    ThreadContext.remove("ctx");
                }
            });

            CompletableFuture<SourceRegistry.TransitData> transitFuture = CompletableFuture.supplyAsync(() -> {
                ThreadContext.put("ctx", requestId.toString());
                try {
                    processManager.setProgress(requestId, L.msg("simulation.progress.transit"));
                    SourceRegistry.TransitData td = sourceRegistry.getTransit(transitYear, transitName);
                    transitPreparer.prepare(td.schedule(), td.vehicles(), sharedNetwork);
                    return td;
                } finally {
                    ThreadContext.remove("ctx");
                }
            });

            PopulationPrepResult popResult;
            SourceRegistry.TransitData transitData;
            try {
                CompletableFuture.allOf(populationFuture, transitFuture).join();
                popResult = populationFuture.get();
                transitData = transitFuture.get();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancellationException ce) throw ce;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }

            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_POPULATION_COMPLETE);
            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_TRANSIT_COMPLETE);
            checkCancelled(requestId);

            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_CONFIG_STARTED);
            processManager.setProgress(requestId, L.msg("simulation.progress.config"));

            Config baselineConfig = configBuilder.build(
                    request, requestId, "baseline", popResult.plansFile, popResult.vehiclesFile);
            Config policyConfig = configBuilder.build(
                    request, requestId, "policy", popResult.plansFile, popResult.vehiclesFile);

            ZonePolicyIndex policyIndex = ZonePolicyIndex.build(request.getZones(), popResult.zoneLinkSets);
            ZoneEnforcementModule enforcementModule = new ZoneEnforcementModule(policyIndex);

            MatsimRunner.normalizeModesInPopulation(popResult.population);

            Population policyPopulation = PopulationUtils.readPopulation(popResult.plansFile.toString());
            MatsimRunner.normalizeModesInPopulation(policyPopulation);

            messageSender.sendLifecycle(emitter, MessageType.PA_PREPROCESSING_CONFIG_COMPLETE);
            checkCancelled(requestId);

            scenarioStateService.updateStatus(requestId, ScenarioStatus.SIMULATING);
            processManager.setProgress(requestId, L.msg("simulation.progress.simulations"));

            TransitSchedule ts = transitData.schedule();
            Vehicles tv = transitData.vehicles();

            messageSender.sendLifecycle(emitter, MessageType.PA_SIMULATION_BASE_STARTED);
            messageSender.sendLifecycle(emitter, MessageType.PA_SIMULATION_POLICY_STARTED);

            CountDownLatch baselineInitGate = new CountDownLatch(1);

            log.info(L.msg("simulation.stage.baseline"));
            CompletableFuture<SimulationResult> baselineFuture = CompletableFuture.supplyAsync(() -> {
                ThreadContext.put("ctx", requestId.toString());
                try {
                    return matsimRunner.runSimulation(
                            baselineConfig, network, ts, tv,
                            popResult.population, popResult.vehicles,
                            "baseline", requestId, processManager, baselineInitGate);
                } catch (Exception e) {
                    baselineInitGate.countDown();
                    throw e;
                } finally {
                    ThreadContext.remove("ctx");
                }
            });

            try {
                baselineInitGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            log.info(L.msg("simulation.stage.policy"));
            CompletableFuture<SimulationResult> policyFuture = CompletableFuture.supplyAsync(() -> {
                ThreadContext.put("ctx", requestId.toString());
                try {
                    return matsimRunner.runSimulation(
                            policyConfig, network, ts, tv,
                            policyPopulation, popResult.vehicles,
                            "policy", requestId, processManager, null, enforcementModule);
                } finally {
                    ThreadContext.remove("ctx");
                }
            });

            SimulationResult baselineResult;
            SimulationResult policyResult;
            try {
                CompletableFuture.allOf(baselineFuture, policyFuture).join();
                baselineResult = baselineFuture.get();
                policyResult = policyFuture.get();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancellationException ce) throw ce;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }

            messageSender.sendLifecycle(emitter, MessageType.PA_SIMULATION_BASE_COMPLETE);
            messageSender.sendLifecycle(emitter, MessageType.PA_SIMULATION_POLICY_COMPLETE);

            checkCancelled(requestId);
            scenarioStateService.updateStatus(requestId, ScenarioStatus.POSTPROCESSING);
            processManager.setProgress(requestId, L.msg("simulation.progress.postprocessing"));
            log.info(L.msg("simulation.stage.postprocess"));

            outputManager.processOutput(requestId, emitter, request, popResult.vehicles,
                    popResult.personCount, networkNodes, networkLinks, popResult.simulationAreaKm2,
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

    private record PopulationPrepResult(
            Population population, Vehicles vehicles,
            Path plansFile, Path vehiclesFile,
            int personCount, double simulationAreaKm2,
            List<ZoneLinkSet> zoneLinkSets) {}

    void checkCancelled(UUID requestId) {
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
        messageSender.sendMessage(emitter, MessageType.PA_CANCELLED_PROCESS,
                Map.of("status", "CANCELLED", "reason", "user_cancelled"));
        messageSender.complete(emitter);
        scenarioStateService.cleanupOutputData(requestId);
        processManager.signalCancellationComplete(requestId);
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
