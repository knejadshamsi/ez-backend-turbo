package org.mobility.start.constructor;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.StrategyConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;

@Component
public class ConfigConstructor {
    @Value("${matsim.input-directory}")
    private String inputDirectory;

    public Config constructConfig(Map<String, Object> request) {
        Path basePath = Paths.get(System.getProperty("user.dir"));
        Path configFilePath = basePath.resolve(inputDirectory).resolve("config.xml").normalize();
        
        if (!Files.exists(configFilePath)) {
            throw new RuntimeException("Config file not found: " + configFilePath);
        }

        Config config = ConfigUtils.loadConfig(configFilePath.toString());
        
        config.controler().setCreateGraphs(false);
        config.controler().setWriteEventsInterval(0);
        config.controler().setWritePlansInterval(0);
        
        config.strategy().clearStrategySettings();
        
        StrategyConfigGroup.StrategySettings reRoute = new StrategyConfigGroup.StrategySettings();
        reRoute.setStrategyName("ReRoute");
        reRoute.setWeight(1.0);
        config.strategy().addStrategySettings(reRoute);
        
        config.strategy().setMaxAgentPlanMemorySize(1);

        Map<String, Object> settings = (Map<String, Object>) request.get("settings");
        if (settings == null) {
            throw new RuntimeException("Settings are required");
        }

        Object iterationsObj = settings.get("simulationIterations");
        if (iterationsObj == null) {
            throw new RuntimeException("simulationIterations is required");
        }

        int iterations;
        if (iterationsObj instanceof Integer) {
            iterations = (Integer) iterationsObj;
        } else if (iterationsObj instanceof Number) {
            iterations = ((Number) iterationsObj).intValue();
        } else {
            throw new RuntimeException("simulationIterations must be a number");
        }

        if (iterations < 1 || iterations > 10) {
            throw new RuntimeException("simulationIterations must be between 1 and 10");
        }

        config.controler().setLastIteration(iterations);

        if (settings.containsKey("modeRestriction")) {
            Map<String, List<Integer>> restrictions = (Map<String, List<Integer>>) settings.get("modeRestriction");
            
            for (Map.Entry<String, List<Integer>> entry : restrictions.entrySet()) {
                String mode = entry.getKey().replace("Restrictions", "");
                List<Integer> values = entry.getValue();
                
                if (values.size() != 4) {
                    throw new RuntimeException("Invalid mode restrictions: must contain exactly 4 values");
                }

                config.planCalcScore().getOrCreateModeParams(mode)
                    .setConstant(values.get(0))
                    .setMarginalUtilityOfDistance(values.get(1))
                    .setMarginalUtilityOfTraveling(values.get(2))
                    .setMonetaryDistanceRate(values.get(3));
            }
        }

        addActivityParams(config, "home", 12 * 3600);
        addActivityParams(config, "work", 8 * 3600);
        addActivityParams(config, "shopping", 2 * 3600);
        addActivityParams(config, "leisure", 2 * 3600);
        addActivityParams(config, "night_shift", 8 * 3600);

        config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.queue);
        config.qsim().setFlowCapFactor(1.0);
        config.qsim().setStorageCapFactor(1.0);
        config.qsim().setRemoveStuckVehicles(true);
        config.qsim().setStuckTime(10.0);
        config.qsim().setNumberOfThreads(1);

        config.global().setCoordinateSystem("EPSG:3857");
        config.global().setNumberOfThreads(1);

        config.parallelEventHandling().setNumberOfThreads(1);
        
        return config;
    }

    private void addActivityParams(Config config, String type, double typicalDuration) {
        PlanCalcScoreConfigGroup.ActivityParams params = new PlanCalcScoreConfigGroup.ActivityParams(type);
        params.setTypicalDuration(typicalDuration);
        config.planCalcScore().addActivityParams(params);
    }
}
