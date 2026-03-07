package ez.backend.turbo.simulation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.population.Person;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BanViolationTracker implements PersonMoneyEventHandler {

    private static final String ZONE_BAN_PURPOSE = "zone_ban";

    private final Set<Id<Person>> violators = ConcurrentHashMap.newKeySet();

    @Override
    public void reset(int iteration) {
    }

    @Override
    public void handleEvent(PersonMoneyEvent event) {
        if (ZONE_BAN_PURPOSE.equals(event.getPurpose())) {
            violators.add(event.getPersonId());
        }
    }

    public Set<Id<Person>> getViolators() {
        return Collections.unmodifiableSet(violators);
    }
}
