package org.example;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.Controler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZeroEmissionZoneModule {
    private static final Logger logger = LoggerFactory.getLogger(ZeroEmissionZoneModule.class);
    private final Scenario scenario;
    private final Controler controler;
    private final StatisticsCollector statsCollector;
    private final ZeroEmissionZoneScoring scoringHandler;

    public ZeroEmissionZoneModule(Scenario scenario, Controler controler) {
        this.scenario = scenario;
        this.controler = controler;
        
        // Initialize StatisticsCollector and ZeroEmissionZoneScoring
        this.statsCollector = new StatisticsCollector();
        this.scoringHandler = new ZeroEmissionZoneScoring(scenario, statsCollector);
    }

    public void install() {
        logger.info("Installing ZeroEmissionZoneModule...");

        // Register the scoring handler and event handler directly with the controler
        controler.getEvents().addHandler(scoringHandler);
        controler.setScoringFunctionFactory(scoringHandler);

        logger.info("ZeroEmissionZoneModule installed successfully");
    }
}
