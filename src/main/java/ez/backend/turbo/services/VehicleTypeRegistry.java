package ez.backend.turbo.services;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.utils.L;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehiclesFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class VehicleTypeRegistry {

    private static final Logger log = LogManager.getLogger(VehicleTypeRegistry.class);

    private static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "zeroEmission", "nearZeroEmission", "lowEmission", "midEmission", "highEmission"
    );

    private final Map<String, VehicleType> vehicleTypes;
    private final Path hbefaWarmFile;
    private final Path hbefaColdFile;

    public VehicleTypeRegistry(StartupValidator startupValidator) {
        this.hbefaWarmFile = startupValidator.getHbefaWarmFile();
        this.hbefaColdFile = startupValidator.getHbefaColdFile();
        log.info(L.msg("source.hbefa.validated"),
                hbefaWarmFile.getFileName(), hbefaColdFile.getFileName());

        this.vehicleTypes = loadVehicleTypes(startupValidator.getVehicleTypesFile());
    }

    public VehicleType get(String frontendName) {
        VehicleType type = vehicleTypes.get(frontendName);
        if (type == null) {
            throw new IllegalArgumentException(L.msg("source.vehicle.category.unknown") + ": " + frontendName);
        }
        return type;
    }

    public Map<String, VehicleType> getAll() {
        return Collections.unmodifiableMap(vehicleTypes);
    }

    public Path getHbefaWarmFile() {
        return hbefaWarmFile;
    }

    public Path getHbefaColdFile() {
        return hbefaColdFile;
    }

    @SuppressWarnings("unchecked")
    private Map<String, VehicleType> loadVehicleTypes(Path yamlFile) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> root;
        try {
            root = mapper.readValue(yamlFile.toFile(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(L.msg("source.vehicle.types.parse.failed") + ": " + yamlFile, e);
        }

        Object entries = root.get("vehicleTypes");
        if (!(entries instanceof Map)) {
            throw new IllegalStateException(L.msg("source.vehicle.types.missing.map"));
        }
        Map<String, Map<String, String>> typeDefs = (Map<String, Map<String, String>>) entries;

        for (String required : REQUIRED_CATEGORIES) {
            if (!typeDefs.containsKey(required)) {
                throw new IllegalStateException(L.msg("source.vehicle.types.missing.category") + ": " + required);
            }
        }

        VehiclesFactory factory = VehicleUtils.getFactory();
        Map<String, VehicleType> result = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, String>> entry : typeDefs.entrySet()) {
            String name = entry.getKey();
            Map<String, String> attrs = entry.getValue();

            VehicleType vt = factory.createVehicleType(Id.create(name, VehicleType.class));
            EngineInformation engineInfo = vt.getEngineInformation();
            VehicleUtils.setHbefaVehicleCategory(engineInfo, attrs.get("hbefaCategory"));
            VehicleUtils.setHbefaTechnology(engineInfo, attrs.get("hbefaTechnology"));
            VehicleUtils.setHbefaSizeClass(engineInfo, attrs.get("hbefaSizeClass"));
            VehicleUtils.setHbefaEmissionsConcept(engineInfo, attrs.get("hbefaEmissionsConcept"));

            result.put(name, vt);
        }

        log.info(L.msg("source.vehicle.types.loaded"), result.size(), yamlFile.getFileName());
        return result;
    }
}
