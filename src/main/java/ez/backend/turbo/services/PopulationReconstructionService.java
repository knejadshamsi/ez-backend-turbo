package ez.backend.turbo.services;

import ez.backend.turbo.config.StartupValidator;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PopulationReconstructionService {

    private static final Logger log = LogManager.getLogger(PopulationReconstructionService.class);
    private static final int BATCH_SIZE = 1000;

    private final SourceRegistry sourceRegistry;
    private final Path dataRoot;

    public PopulationReconstructionService(SourceRegistry sourceRegistry,
                                           StartupValidator startupValidator) {
        this.sourceRegistry = sourceRegistry;
        this.dataRoot = startupValidator.getDataRoot();
    }

    public Path reconstructAndSample(Set<String> personIds, int popYear, String popName,
                                     UUID requestId, int percentage) {
        JdbcTemplate popDb = sourceRegistry.getPopulationDb(popYear, popName);
        Population population = PopulationUtils.createPopulation(
                org.matsim.core.config.ConfigUtils.createConfig());

        List<String> idList = new ArrayList<>(personIds);
        for (int i = 0; i < idList.size(); i += BATCH_SIZE) {
            List<String> batch = idList.subList(i, Math.min(i + BATCH_SIZE, idList.size()));
            reconstructBatch(popDb, population, batch);
        }

        MatsimRandom.reset(4711L);
        PopulationUtils.sampleDown(population, percentage / 100.0);

        Path outputDir = dataRoot.resolve("output").resolve(requestId.toString());
        try {
            Files.createDirectories(outputDir);
        } catch (Exception e) {
            throw new IllegalStateException(L.msg("population.reconstruct.dir.failed") + ": " + outputDir, e);
        }

        Path plansFile = outputDir.resolve("plans.xml");
        new PopulationWriter(population).write(plansFile.toString());

        log.info(L.msg("population.reconstruct.complete"), population.getPersons().size());
        return plansFile;
    }

    private void reconstructBatch(JdbcTemplate popDb, Population population, List<String> personIds) {
        String placeholders = placeholders(personIds.size());
        Object[] params = personIds.toArray();

        Map<String, List<ActivityRow>> activitiesByPerson = new HashMap<>();
        popDb.query(
                "SELECT person_id, seq, activity_type, x, y, end_time FROM activities "
                        + "WHERE person_id IN (" + placeholders + ") ORDER BY person_id, seq",
                rs -> {
                    String pid = rs.getString("person_id");
                    activitiesByPerson.computeIfAbsent(pid, k -> new ArrayList<>()).add(
                            new ActivityRow(rs.getInt("seq"), rs.getString("activity_type"),
                                    rs.getDouble("x"), rs.getDouble("y"),
                                    rs.getObject("end_time") != null ? rs.getDouble("end_time") : null));
                },
                params);

        Map<String, List<LegRow>> legsByPerson = new HashMap<>();
        popDb.query(
                "SELECT person_id, seq, mode FROM legs "
                        + "WHERE person_id IN (" + placeholders + ") ORDER BY person_id, seq",
                rs -> {
                    String pid = rs.getString("person_id");
                    legsByPerson.computeIfAbsent(pid, k -> new ArrayList<>()).add(
                            new LegRow(rs.getInt("seq"), rs.getString("mode")));
                },
                params);

        for (String personId : personIds) {
            List<ActivityRow> activities = activitiesByPerson.getOrDefault(personId, List.of());
            List<LegRow> legs = legsByPerson.getOrDefault(personId, List.of());
            if (activities.isEmpty()) continue;

            Person person = population.getFactory().createPerson(Id.createPersonId(personId));
            Plan plan = population.getFactory().createPlan();

            List<Object> elements = interleave(activities, legs);
            for (Object element : elements) {
                if (element instanceof ActivityRow a) {
                    Activity activity = population.getFactory().createActivityFromCoord(
                            a.type, new Coord(a.x, a.y));
                    if (a.endTime != null) {
                        activity.setEndTime(a.endTime);
                    }
                    plan.addActivity(activity);
                } else if (element instanceof LegRow l) {
                    Leg leg = population.getFactory().createLeg(l.mode);
                    plan.addLeg(leg);
                }
            }

            person.addPlan(plan);
            person.setSelectedPlan(plan);
            population.addPerson(person);
        }
    }

    private List<Object> interleave(List<ActivityRow> activities, List<LegRow> legs) {
        List<Object> merged = new ArrayList<>(activities.size() + legs.size());
        merged.addAll(activities);
        merged.addAll(legs);
        merged.sort((a, b) -> {
            int seqA = a instanceof ActivityRow ar ? ar.seq : ((LegRow) a).seq;
            int seqB = b instanceof ActivityRow br ? br.seq : ((LegRow) b).seq;
            return Integer.compare(seqA, seqB);
        });
        return merged;
    }

    private String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private record ActivityRow(int seq, String type, double x, double y, Double endTime) {}
    private record LegRow(int seq, String mode) {}
}
