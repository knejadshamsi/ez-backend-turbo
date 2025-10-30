package org.mobility.start.sim;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class Tier2DecisionModule {
    public boolean shouldEnterZone(Person person, Plan plan, List<String> zoneLinks, double penalty) {
        if (plan == null || plan.getScore() == null) {
            return false;
        }

        if (penalty == Double.NEGATIVE_INFINITY) {
            return false;
        }

        double currentScore = plan.getScore();
        double potentialScore = currentScore - penalty;

        if (potentialScore == Double.NEGATIVE_INFINITY) {
            return false;
        }

        double averageScore = person.getPlans().stream()
            .filter(p -> p.getScore() != null)
            .mapToDouble(Plan::getScore)
            .average()
            .orElse(Double.NEGATIVE_INFINITY);

        if (averageScore == Double.NEGATIVE_INFINITY) {
            return false;
        }

        int zoneActivities = 0;
        int totalActivities = 0;

        for (var element : plan.getPlanElements()) {
            if (element instanceof Activity) {
                Activity activity = (Activity) element;
                totalActivities++;
                if (activity.getLinkId() != null && zoneLinks.contains(activity.getLinkId().toString())) {
                    zoneActivities++;
                }
            }
        }

        if (totalActivities == 0) {
            return false;
        }

        if (zoneActivities > totalActivities / 2) {
            return true;
        }

        return potentialScore > averageScore * 0.75;
    }
}
