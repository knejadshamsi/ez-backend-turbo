package ez.backend.turbo.simulation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.population.Person;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PersonMoneyEventCollector implements PersonMoneyEventHandler {

    private final ConcurrentHashMap<Id<Person>, List<MoneyRecord>> events = new ConcurrentHashMap<>();

    public record MoneyRecord(double time, double amount, String purpose, String reference, String transactionPartner) {}

    @Override
    public void reset(int iteration) {
        events.clear();
    }

    @Override
    public void handleEvent(PersonMoneyEvent event) {
        MoneyRecord record = new MoneyRecord(
                event.getTime(),
                event.getAmount(),
                event.getPurpose(),
                event.getReference(),
                event.getTransactionPartner());
        events.computeIfAbsent(event.getPersonId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(record);
    }

    public Map<Id<Person>, List<MoneyRecord>> getEvents() {
        return new HashMap<>(events);
    }
}
