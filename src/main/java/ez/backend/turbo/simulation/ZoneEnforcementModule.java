package ez.backend.turbo.simulation;

import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;

public class ZoneEnforcementModule extends AbstractModule {

    private final ZonePolicyIndex index;

    public ZoneEnforcementModule(ZonePolicyIndex index) {
        this.index = index;
    }

    @Override
    public void install() {
        bind(ZonePolicyIndex.class).toInstance(index);
        bind(ZoneEnforcementHandler.class).asEagerSingleton();
        addEventHandlerBinding().to(ZoneEnforcementHandler.class);

        if (index.hasAnyBans()) {
            TravelDisutilityFactory baseFactory =
                    new RandomizingTimeDistanceTravelDisutilityFactory("car", getConfig());
            addTravelDisutilityFactoryBinding("car").toInstance(
                    new ZoneBanTravelDisutility(baseFactory, index));
        }
    }
}
