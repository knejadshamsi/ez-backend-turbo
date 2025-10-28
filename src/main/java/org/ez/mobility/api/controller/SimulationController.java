package org.ez.mobility.api.controller;

import org.ez.mobility.api.SimulationRequest;
import org.ez.mobility.core.SimulationManager;
import org.ez.mobility.ez.PolicyEngine.PolicyConfigurationException;
import org.ez.mobility.ez.TransitConfigGroup.TransitConfigurationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/simulations")
@Validated
public class SimulationController {
    private static final Logger logger = LoggerFactory.getLogger(SimulationController.class);
    private final SimulationManager simulationManager;

    public SimulationController(SimulationManager simulationManager) {
        this.simulationManager = simulationManager;
    }

    /**
     * Initiates a new simulation based on the provided configuration.
     *
     * @param request Simulation configuration details
     * @return ResponseEntity with the unique request ID and initial status
     */
    @PostMapping("/start")
    public ResponseEntity<?> startSimulation(@Valid @RequestBody SimulationRequest request) {
        String requestId = UUID.randomUUID().toString();
        logger.info("Received simulation request with ID: {}", requestId);

        try {
            validateRequest(request);
            boolean simulationStarted = simulationManager.runSimulation(requestId, request);

            if (simulationStarted) {
                Map<String, String> response = new HashMap<>();
                response.put("requestId", requestId);
                response.put("status", "RECEIVED");
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body("Simulation could not be started");
            }
        } catch (PolicyConfigurationException e) {
            logger.error("Policy configuration error for request {}: {}", requestId, e.getMessage());
            return ResponseEntity.badRequest().body("Invalid policy configuration: " + e.getMessage());
        } catch (TransitConfigurationException e) {
            logger.error("Transit configuration error for request {}: {}", requestId, e.getMessage());
            return ResponseEntity.badRequest().body("Invalid transit configuration: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Validation error for request {}: {}", requestId, e.getMessage());
            return ResponseEntity.badRequest().body("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error processing request " + requestId, e);
            return ResponseEntity.internalServerError().body("Internal server error: " + e.getMessage());
        }
    }

    /**
     * Retrieves the current status of a simulation.
     *
     * @param requestId Unique identifier for the simulation
     * @return ResponseEntity with the current simulation status
     */
    @GetMapping("/{requestId}/status")
    public ResponseEntity<?> getSimulationStatus(@PathVariable String requestId) {
        try {
            Map<String, Object> status = simulationManager.getSimulationStatus(requestId);
            if (status != null) {
                return ResponseEntity.ok(status);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error retrieving status for request " + requestId, e);
            return ResponseEntity.internalServerError().body("Error retrieving simulation status");
        }
    }

    /**
     * Cancels an ongoing simulation.
     *
     * @param requestId Unique identifier for the simulation
     * @return ResponseEntity indicating cancellation status
     */
    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<?> cancelSimulation(@PathVariable String requestId) {
        try {
            boolean cancelled = simulationManager.cancelSimulation(requestId);
            if (cancelled) {
                Map<String, String> response = new HashMap<>();
                response.put("status", "CANCELLED");
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error cancelling simulation " + requestId, e);
            return ResponseEntity.internalServerError().body("Error cancelling simulation");
        }
    }

    private void validateRequest(SimulationRequest request) {
        if (request.getOperatingStart().equals(request.getOperatingEnd())) {
            throw new IllegalArgumentException("Operating start and end times cannot be the same");
        }

        if (request.getPolicy() != null) {
            for (SimulationRequest.PolicyEntry policy : request.getPolicy()) {
                validatePolicyEntry(policy);
            }
        }
    }

    private void validatePolicyEntry(SimulationRequest.PolicyEntry policy) {
        if (policy.getMode() == null || policy.getMode().trim().isEmpty()) {
            throw new IllegalArgumentException("Policy mode cannot be empty");
        }

        switch (policy.getMode()) {
            case "fixed":
            case "hourly":
                validatePriceBasedPolicy(policy);
                break;
            case "vehicle":
                validateVehiclePolicy(policy);
                break;
            case "banned":
                validateBannedPolicy(policy);
                break;
            case "transit_stop":
                validateTransitStopPolicy(policy);
                break;
            case "transit_line":
                validateTransitLinePolicy(policy);
                break;
            default:
                throw new IllegalArgumentException("Invalid policy mode: " + policy.getMode());
        }
    }

    private void validatePriceBasedPolicy(SimulationRequest.PolicyEntry policy) {
        if (policy.getOptions() == null || policy.getOptions().getPrice() == null) {
            throw new IllegalArgumentException("Price must be specified for " + policy.getMode() + " policy");
        }
        if (policy.getOptions().getPrice() < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        if ("hourly".equals(policy.getMode())) {
            if (policy.getOptions().getInterval() == null) {
                throw new IllegalArgumentException("Interval must be specified for hourly policy");
            }
            if (policy.getOptions().getInterval() < 0.5 || policy.getOptions().getInterval() > 23) {
                throw new IllegalArgumentException("Hourly interval must be between 0.5 and 23 hours");
            }
        }
    }

    private void validateVehiclePolicy(SimulationRequest.PolicyEntry policy) {
        if (policy.getOptions() == null || policy.getOptions().getMode() == null) {
            throw new IllegalArgumentException("Vehicle mode must be specified for vehicle policy");
        }
    }

    private void validateBannedPolicy(SimulationRequest.PolicyEntry policy) {
        if (policy.getOptions() != null && policy.getOptions().getPenalty() != null) {
            if (policy.getOptions().getPenalty() < 0) {
                throw new IllegalArgumentException("Penalty cannot be negative");
            }
        }
    }

    private void validateTransitStopPolicy(SimulationRequest.PolicyEntry policy) {
        if (policy.getOptions() == null || policy.getOptions().getMode() == null) {
            throw new IllegalArgumentException("Transit mode must be specified for transit stop");
        }
        if (!policy.getOptions().getMode().equals("bus") && !policy.getOptions().getMode().equals("metro")) {
            throw new IllegalArgumentException("Transit mode must be either 'bus' or 'metro'");
        }
        String[] coords = policy.getId().split(",");
        if (coords.length != 2) {
            throw new IllegalArgumentException("Transit stop must have x,y coordinates");
        }
        try {
            Double.parseDouble(coords[0]);
            Double.parseDouble(coords[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid transit stop coordinates");
        }
    }

    private void validateTransitLinePolicy(SimulationRequest.PolicyEntry policy) {
        if (policy.getOptions() == null) {
            throw new IllegalArgumentException("Transit line options must be specified");
        }
        if (policy.getOptions().getInterval() == null || policy.getOptions().getInterval() <= 0) {
            throw new IllegalArgumentException("Transit line interval must be positive");
        }
        String[] stops = policy.getId().split(";");
        if (stops.length < 2) {
            throw new IllegalArgumentException("Transit line must have at least 2 stops");
        }
    }
}
