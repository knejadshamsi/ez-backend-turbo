package org.mobility.start.sim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.mobsim.qsim.QSim;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class Tier3Replanner {
    private final JdbcTemplate jdbcTemplate;
    private QSim qsim;

    @Autowired
    public Tier3Replanner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setQSim(QSim qsim) {
        this.qsim = qsim;
    }

    public void replanRoute(Id<Person> personId, List<Id<Link>> zoneLinks) {
        if (qsim == null) {
            return;
        }

        Person person = qsim.getScenario().getPopulation().getPersons().get(personId);
        if (person == null) {
            return;
        }

        Plan plan = person.getSelectedPlan();
        if (plan == null) {
            return;
        }

        boolean hasZoneActivity = false;
        for (var element : plan.getPlanElements()) {
            if (element instanceof Activity) {
                Activity activity = (Activity) element;
                if (activity.getLinkId() != null && zoneLinks.contains(activity.getLinkId())) {
                    hasZoneActivity = true;
                    break;
                }
            }
        }

        if (!hasZoneActivity) {
            return;
        }

        plan.setScore(Double.NEGATIVE_INFINITY);

        for (var element : plan.getPlanElements()) {
            if (element instanceof Leg) {
                Leg leg = (Leg) element;
                if (leg.getRoute() != null) {
                    leg.setRoute(null);
                }
            }
        }

        String avoidLinks = zoneLinks.stream()
            .map(Id::toString)
            .collect(Collectors.joining(","));
        
        plan.getAttributes().putAttribute("avoidLinks", avoidLinks);

        jdbcTemplate.update(
            "UPDATE agents SET stuck = true WHERE agent_id = ? AND plan_id = ?",
            personId.toString(),
            plan.toString()
        );
    }
}
