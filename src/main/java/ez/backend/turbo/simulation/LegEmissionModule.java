package ez.backend.turbo.simulation;

import org.matsim.core.controler.AbstractModule;

public class LegEmissionModule extends AbstractModule {

    private final LegEmissionTracker tracker;

    public LegEmissionModule(LegEmissionTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public void install() {
        bind(LegEmissionTracker.class).toInstance(tracker);
        addEventHandlerBinding().to(LegEmissionTracker.class);
    }
}
