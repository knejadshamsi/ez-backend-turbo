package ez.backend.turbo.endpoints;

import ez.backend.turbo.services.SimulationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/simulate")
    public SseEmitter simulate(@RequestBody SimulationRequest request) {
        return simulationService.startSimulation(request);
    }
}
