package ez.backend.turbo.services;

import ez.backend.turbo.endpoints.SimulationRequest;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehiclesFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class VehicleAssignmentService {

    private static final Logger log = LogManager.getLogger(VehicleAssignmentService.class);

    private static final String[] CATEGORY_ORDER = {
            "zeroEmission", "nearZeroEmission", "lowEmission", "midEmission", "highEmission"
    };

    private final VehicleTypeRegistry vehicleTypeRegistry;

    public VehicleAssignmentService(VehicleTypeRegistry vehicleTypeRegistry) {
        this.vehicleTypeRegistry = vehicleTypeRegistry;
    }

    public Vehicles assign(Population population, SimulationRequest.CarDistribution distribution) {
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        VehiclesFactory factory = vehicles.getFactory();

        for (VehicleType type : vehicleTypeRegistry.getAll().values()) {
            vehicles.addVehicleType(type);
        }

        List<Id<Person>> personIds = new ArrayList<>(population.getPersons().keySet());
        personIds.sort(Comparator.comparing(Id::toString));
        Collections.shuffle(personIds, new Random(4711L));

        int n = personIds.size();
        double[] percentages = {
                distribution.getZeroEmission(),
                distribution.getNearZeroEmission(),
                distribution.getLowEmission(),
                distribution.getMidEmission(),
                distribution.getHighEmission()
        };

        Map<String, Integer> stats = new LinkedHashMap<>();
        double cumulative = 0;
        int assigned = 0;

        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            cumulative += percentages[i];
            int boundary = (i < CATEGORY_ORDER.length - 1)
                    ? (int) Math.floor(n * cumulative / 100.0)
                    : n;

            VehicleType type = vehicleTypeRegistry.get(CATEGORY_ORDER[i]);
            int count = 0;

            while (assigned < boundary) {
                Id<Person> personId = personIds.get(assigned);
                Id<Vehicle> vehicleId = Id.createVehicleId(personId.toString() + "_car");
                vehicles.addVehicle(factory.createVehicle(vehicleId, type));

                Person person = population.getPersons().get(personId);
                VehicleUtils.insertVehicleIdsIntoPersonAttributes(person, Map.of("car", vehicleId));

                assigned++;
                count++;
            }

            stats.put(CATEGORY_ORDER[i], count);
        }

        log.info(L.msg("vehicle.assignment.complete"), n, stats.size());
        return vehicles;
    }
}
