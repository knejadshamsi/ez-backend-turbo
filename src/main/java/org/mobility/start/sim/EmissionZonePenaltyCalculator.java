package org.mobility.start.sim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.scoring.ScoringFunction;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EmissionZonePenaltyCalculator implements IterationEndsListener {
    private final Map<String, Double> penalties = new ConcurrentHashMap<>();

    public void registerPenalty(String vehicleId, double amount) {
        if (amount == Double.NEGATIVE_INFINITY) {
            penalties.put(vehicleId, amount);
            return;
        }

        if (amount <= 0) {
            return;
        }

        Double currentPenalty = penalties.get(vehicleId);
        if (currentPenalty == null || currentPenalty != Double.NEGATIVE_INFINITY) {
            penalties.merge(vehicleId, amount, (a, b) -> 
                a == Double.NEGATIVE_INFINITY || b == Double.NEGATIVE_INFINITY ? 
                Double.NEGATIVE_INFINITY : a + b);
        }
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        penalties.forEach((vehicleId, penalty) -> {
            int underscoreIndex = vehicleId.indexOf('_');
            if (underscoreIndex == -1) {
                return;
            }

            String personId = vehicleId.substring(underscoreIndex + 1);
            Person person = event.getServices().getScenario().getPopulation()
                .getPersons().get(Id.createPersonId(personId));
            
            if (person == null) {
                return;
            }

            Plan plan = person.getSelectedPlan();
            if (plan == null) {
                return;
            }

            if (penalty == Double.NEGATIVE_INFINITY) {
                plan.setScore(Double.NEGATIVE_INFINITY);
                return;
            }

            ScoringFunction scoringFunction = event.getServices()
                .getScoringFunctionFactory()
                .createNewScoringFunction(person);

            scoringFunction.addMoney(-penalty);
            scoringFunction.finish();

            Double currentScore = plan.getScore();
            if (currentScore == null) {
                plan.setScore(-penalty);
            } else {
                plan.setScore(currentScore - penalty);
            }
        });

        penalties.clear();
    }
}
