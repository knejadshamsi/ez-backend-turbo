package org.mobility.start.sim;

import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.router.TripRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class EmissionZoneReplannerFactory {
    private final EmissionZoneHandler emissionHandler;
    private final EmissionZonePenaltyCalculator penaltyCalculator;
    private final Tier3Replanner tier3Replanner;
    private final Tier2DecisionModule tier2DecisionModule;

    @Autowired
    public EmissionZoneReplannerFactory(
        EmissionZoneHandler emissionHandler,
        EmissionZonePenaltyCalculator penaltyCalculator,
        Tier3Replanner tier3Replanner,
        Tier2DecisionModule tier2DecisionModule
    ) {
        this.emissionHandler = emissionHandler;
        this.penaltyCalculator = penaltyCalculator;
        this.tier3Replanner = tier3Replanner;
        this.tier2DecisionModule = tier2DecisionModule;
    }

    public void initialize(QSim qsim, TripRouter tripRouter) {
        if (qsim == null) {
            throw new IllegalArgumentException("QSim cannot be null");
        }
        emissionHandler.setQSim(qsim);
        tier3Replanner.setQSim(qsim);
    }

    public EmissionZoneHandler getHandler() {
        return emissionHandler;
    }

    public EmissionZonePenaltyCalculator getPenaltyCalculator() {
        return penaltyCalculator;
    }

    public Tier3Replanner getTier3Replanner() {
        return tier3Replanner;
    }

    public Tier2DecisionModule getTier2DecisionModule() {
        return tier2DecisionModule;
    }
}
