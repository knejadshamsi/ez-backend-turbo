package ez.backend.turbo.simulation;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

public class ZoneBanTravelDisutility implements TravelDisutilityFactory {

    private static final double BAN_COST = Double.MAX_VALUE;

    private final TravelDisutilityFactory delegate;
    private final ZonePolicyIndex index;

    public ZoneBanTravelDisutility(TravelDisutilityFactory delegate, ZonePolicyIndex index) {
        this.delegate = delegate;
        this.index = index;
    }

    @Override
    public TravelDisutility createTravelDisutility(TravelTime timeCalculator) {
        TravelDisutility base = delegate.createTravelDisutility(timeCalculator);

        return new TravelDisutility() {
            @Override
            public double getLinkTravelDisutility(Link link, double time,
                                                  Person person, Vehicle vehicle) {
                double cost = base.getLinkTravelDisutility(link, time, person, vehicle);
                if (vehicle == null) return cost;
                String vt = vehicle.getType().getId().toString();
                if (index.isBanned(link.getId(), vt, time)) {
                    return cost + BAN_COST;
                }
                return cost;
            }

            @Override
            public double getLinkMinimumTravelDisutility(Link link) {
                return base.getLinkMinimumTravelDisutility(link);
            }
        };
    }
}
