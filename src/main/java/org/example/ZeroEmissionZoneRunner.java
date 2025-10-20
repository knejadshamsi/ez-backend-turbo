package org.example;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunctionFactory;
import com.google.inject.name.Names;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class ZeroEmissionZoneRunner {

    public static void main(String[] args) {
        // Get the current working directory
        String currentPath = System.getProperty("user.dir");
        System.out.println("Current working directory: " + currentPath);

        // Step 1: Load config with absolute path
        String configPath = Paths.get(currentPath, "input", "config.xml").toString();
        System.out.println("Loading config from: " + configPath);

        Config config = ConfigUtils.loadConfig(configPath);

        // Step 2: Set absolute paths for input files
        config.network().setInputFile(Paths.get(currentPath, "input", "network.xml").toString());
        config.plans().setInputFile(Paths.get(currentPath, "input", "population.xml").toString());
        config.vehicles().setVehiclesFile(Paths.get(currentPath, "input", "vehicle_types.xml").toString());
        config.controler().setOutputDirectory(Paths.get(currentPath, "output", "zero-emission-test").toString());

        // Configure QSim for vehicle handling
        config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
        Set<String> mainModes = new HashSet<>();
        mainModes.add("car");
        config.qsim().setMainModes(mainModes);

        // Step 3: Create the scenario
        final Scenario scenario = ScenarioUtils.loadScenario(config);

        // Step 4: Configure zero emission zone link ID
        final Link zezLink = scenario.getNetwork().getLinks().get(Id.createLinkId("2"));
        System.out.println("\nConfiguring zero emission zone on link: " + zezLink.getId());

        // Step 5: Set up the controller
        Controler controler = new Controler(scenario);

        // Add our custom scoring module
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // Bind the link ID as a String
                bind(String.class)
                    .annotatedWith(Names.named("zezLinkId"))
                    .toInstance("2");
                
                // Bind our custom scoring class
                bind(ScoringFunctionFactory.class).to(ZeroEmissionZoneScoring.class);
                
                // Add as event handler
                addEventHandlerBinding().to(ZeroEmissionZoneScoring.class);
            }
        });

        controler.run();
    }
}