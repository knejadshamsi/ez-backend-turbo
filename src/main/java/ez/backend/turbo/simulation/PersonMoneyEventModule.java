package ez.backend.turbo.simulation;

import org.matsim.core.controler.AbstractModule;

public class PersonMoneyEventModule extends AbstractModule {

    private final PersonMoneyEventCollector collector;

    public PersonMoneyEventModule(PersonMoneyEventCollector collector) {
        this.collector = collector;
    }

    @Override
    public void install() {
        bind(PersonMoneyEventCollector.class).toInstance(collector);
        addEventHandlerBinding().to(PersonMoneyEventCollector.class);
    }
}
