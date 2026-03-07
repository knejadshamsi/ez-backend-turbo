package ez.backend.turbo.simulation;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.router.PlanRouter;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class ForcedInnovationListener implements IterationEndsListener {

    private static final int CYCLE_MODE_CHANGE = 0;
    private static final int CYCLE_REROUTE = 1;
    private static final int CYCLE_TIME_SHIFT = 2;
    private static final int CYCLE_LENGTH = 3;

    private static final String[] ALTERNATIVE_MODES = {"pt", "bike", "walk"};

    private final Scenario scenario;
    private final Provider<TripRouter> tripRouterProvider;
    private final ActivityFacilities facilities;
    private final BanViolationTracker tracker;
    private final Config config;
    private final TimeInterpretation timeInterpretation;
    private final Random random = new Random(9182L);

    @Inject
    ForcedInnovationListener(Scenario scenario,
                             Provider<TripRouter> tripRouterProvider,
                             ActivityFacilities facilities,
                             BanViolationTracker tracker,
                             Config config,
                             TimeInterpretation timeInterpretation) {
        this.scenario = scenario;
        this.tripRouterProvider = tripRouterProvider;
        this.facilities = facilities;
        this.tracker = tracker;
        this.config = config;
        this.timeInterpretation = timeInterpretation;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        int iter = event.getIteration();
        int lastIteration = config.controller().getLastIteration();
        double fraction = config.replanning().getFractionOfIterationsToDisableInnovation();
        int cutoff = (int) (lastIteration * fraction);
        if (iter >= cutoff) return;

        Set<Id<Person>> violators = tracker.getViolators();
        if (violators.isEmpty()) return;

        Population population = scenario.getPopulation();
        int maxPlans = config.replanning().getMaxAgentPlanMemorySize();
        int cycle = iter % CYCLE_LENGTH;

        TripRouter tripRouter = tripRouterProvider.get();
        PlanRouter planRouter = new PlanRouter(tripRouter, facilities, timeInterpretation);

        for (Id<Person> personId : violators) {
            Person person = population.getPersons().get(personId);
            if (person == null) continue;

            Plan selected = person.getSelectedPlan();
            if (selected == null) continue;

            Plan newPlan = switch (cycle) {
                case CYCLE_MODE_CHANGE -> buildModeChangePlan(person, selected, planRouter);
                case CYCLE_REROUTE -> buildReroutePlan(person, selected, planRouter);
                case CYCLE_TIME_SHIFT -> buildTimeShiftPlan(person, selected);
                default -> null;
            };

            if (newPlan == null) continue;

            enforceMaxPlans(person, maxPlans);
            person.addPlan(newPlan);
            person.setSelectedPlan(newPlan);
        }
    }

    private Plan buildModeChangePlan(Person person, Plan selected, PlanRouter planRouter) {
        String targetMode = pickUntriedMode(person);
        Plan newPlan = clonePlan(person, selected);

        for (PlanElement element : newPlan.getPlanElements()) {
            if (element instanceof Leg leg && isCarOrRide(leg.getMode())) {
                leg.setMode(targetMode);
                leg.setRoute(null);
                leg.setRoutingMode(targetMode);
            }
        }

        planRouter.run(newPlan);
        return newPlan;
    }

    private Plan buildReroutePlan(Person person, Plan selected, PlanRouter planRouter) {
        Plan newPlan = clonePlan(person, selected);

        for (PlanElement element : newPlan.getPlanElements()) {
            if (element instanceof Leg leg && "car".equals(leg.getMode())) {
                leg.setRoute(null);
            }
        }

        planRouter.run(newPlan);
        return newPlan;
    }

    private Plan buildTimeShiftPlan(Person person, Plan selected) {
        double mutationRange = config.timeAllocationMutator().getMutationRange();
        double simEnd = config.qsim().getEndTime().seconds();
        Plan newPlan = clonePlan(person, selected);

        List<PlanElement> elements = newPlan.getPlanElements();
        for (int i = 0; i < elements.size() - 1; i++) {
            if (elements.get(i) instanceof Activity act && act.getEndTime().isDefined()) {
                double shift = (random.nextDouble() * 2 - 1) * mutationRange;
                double newTime = Math.max(0, Math.min(simEnd, act.getEndTime().seconds() + shift));
                act.setEndTime(newTime);
            }
        }

        return newPlan;
    }

    private String pickUntriedMode(Person person) {
        Set<String> triedModes = new HashSet<>();
        for (Plan plan : person.getPlans()) {
            for (PlanElement element : plan.getPlanElements()) {
                if (element instanceof Leg leg) {
                    triedModes.add(leg.getMode());
                }
            }
        }

        for (String mode : ALTERNATIVE_MODES) {
            if (!triedModes.contains(mode)) return mode;
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        String bestMode = ALTERNATIVE_MODES[0];
        for (Plan plan : person.getPlans()) {
            if (plan.getScore() == null) continue;
            boolean hasAltMode = false;
            for (PlanElement element : plan.getPlanElements()) {
                if (element instanceof Leg leg) {
                    for (String alt : ALTERNATIVE_MODES) {
                        if (alt.equals(leg.getMode())) {
                            hasAltMode = true;
                            if (plan.getScore() > bestScore) {
                                bestScore = plan.getScore();
                                bestMode = alt;
                            }
                            break;
                        }
                    }
                }
            }
        }
        return bestMode;
    }

    private Plan clonePlan(Person person, Plan source) {
        PopulationFactory factory = scenario.getPopulation().getFactory();
        Plan newPlan = factory.createPlan();
        newPlan.setPerson(person);
        for (PlanElement element : source.getPlanElements()) {
            if (element instanceof Activity act) {
                Activity copy = factory.createActivityFromCoord(act.getType(), act.getCoord());
                copy.setLinkId(act.getLinkId());
                if (act.getEndTime().isDefined()) copy.setEndTime(act.getEndTime().seconds());
                if (act.getMaximumDuration().isDefined())
                    copy.setMaximumDuration(act.getMaximumDuration().seconds());
                newPlan.addActivity(copy);
            } else if (element instanceof Leg leg) {
                Leg copy = factory.createLeg(leg.getMode());
                copy.setRoute(leg.getRoute() != null ? leg.getRoute().clone() : null);
                copy.setRoutingMode(leg.getRoutingMode());
                newPlan.addLeg(copy);
            }
        }
        return newPlan;
    }

    private static boolean isCarOrRide(String mode) {
        return "car".equals(mode) || "ride".equals(mode);
    }

    private void enforceMaxPlans(Person person, int maxPlans) {
        while (person.getPlans().size() >= maxPlans) {
            Plan worst = null;
            double worstScore = Double.MAX_VALUE;
            for (Plan plan : person.getPlans()) {
                if (plan == person.getSelectedPlan()) continue;
                double score = plan.getScore() != null ? plan.getScore() : Double.MAX_VALUE;
                if (score < worstScore) {
                    worstScore = score;
                    worst = plan;
                }
            }
            if (worst != null) {
                person.removePlan(worst);
            } else {
                break;
            }
        }
    }
}
