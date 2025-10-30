package org.mobility.api;

import org.mobility.cancel.CancellationManager;
import org.mobility.start.InputManager;
import org.mobility.start.sim.SimulationRunner;
import org.mobility.start.output.OutputManager;
import org.mobility.status.StatusManager;
import org.mobility.start.valid.ValidationManager;
import org.mobility.utils.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/simulation")
public class SimulationController {
    private final ValidationManager validationManager;
    private final InputManager inputManager;
    private final SimulationRunner simulationRunner;
    private final OutputManager outputManager;
    private final CancellationManager cancellationManager;
    private final StatusManager statusManager;

    @Autowired
    public SimulationController(
        @Qualifier("startValidationManager") ValidationManager validationManager,
        InputManager inputManager,
        SimulationRunner simulationRunner,
        OutputManager outputManager,
        CancellationManager cancellationManager,
        StatusManager statusManager
    ) {
        this.validationManager = validationManager;
        this.inputManager = inputManager;
        this.simulationRunner = simulationRunner;
        this.outputManager = outputManager;
        this.cancellationManager = cancellationManager;
        this.statusManager = statusManager;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startSimulation(@RequestBody Map<String, Object> request) {
        WorkflowResult validationResult = validationManager.validate(request);
        if (!validationResult.isSuccess()) {
            return createResponse(validationResult.getCode(), validationResult.getMessage());
        }

        String requestId = (String) request.get("requestId");
        statusManager.updateStatus(requestId, StatusManager.STATUS_VALIDATING, "Validating request");

        WorkflowResult inputResult = inputManager.processInput(validationResult.getData());
        if (!inputResult.isSuccess()) {
            statusManager.updateStatus(requestId, StatusManager.STATUS_FAILED, inputResult.getMessage());
            return createResponse(inputResult.getCode(), inputResult.getMessage());
        }

        WorkflowResult simResult = simulationRunner.runSimulation(inputResult.getData());
        if (!simResult.isSuccess()) {
            statusManager.updateStatus(requestId, StatusManager.STATUS_FAILED, simResult.getMessage());
            return createResponse(simResult.getCode(), simResult.getMessage());
        }

        WorkflowResult outputResult = outputManager.processOutput(simResult.getData());
        if (!outputResult.isSuccess()) {
            statusManager.updateStatus(requestId, StatusManager.STATUS_FAILED, outputResult.getMessage());
            return createResponse(outputResult.getCode(), outputResult.getMessage());
        }

        statusManager.updateStatus(requestId, StatusManager.STATUS_COMPLETED, "Simulation completed successfully");
        return createResponse(200, "Simulation completed successfully");
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelSimulation(@RequestBody Map<String, Object> request) {
        WorkflowResult result = cancellationManager.cancelSimulation(request);
        return createResponse(result.getCode(), result.getMessage());
    }

    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestBody Map<String, Object> request) {
        String requestId = (String) request.get("requestId");
        if (requestId == null) {
            return createResponse(400, "Request ID is required");
        }

        Map<String, Object> status = statusManager.getStatus(requestId);
        return ResponseEntity.ok(status);
    }

    private ResponseEntity<Map<String, Object>> createResponse(int code, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", code);
        response.put("timestamp", LocalDateTime.now());
        response.put("message", message);
        return ResponseEntity.status(code).body(response);
    }
}
