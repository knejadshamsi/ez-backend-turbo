package ez.backend.turbo.preprocess;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PopulationPreprocessor {

    private static final Set<String> VALUED_KEYS = Set.of(
            "--input", "--output", "--polygon", "--keep-attr",
            "--sample", "--source-crs", "--target-crs");
    private static final Set<String> FLAG_KEYS = Set.of(
            "--no-polygon", "--no-attr", "--no-routes",
            "--keep-selected-only", "--no-planless");

    int execute(String[] args) {
        try {
            return run(args);
        } catch (Exception e) {
            System.err.println("Error | Erreur : " + e.getMessage());
            return 1;
        }
    }

    private int run(String[] args) {
        CliArgs cli = CliArgs.parse(args, VALUED_KEYS, FLAG_KEYS);

        String inputPath = cli.require("--input");
        String outputPath = cli.require("--output");
        String sourceCrs = cli.optional("--source-crs", "EPSG:32188");
        String targetCrs = cli.optional("--target-crs", null);

        Population population = PopulationUtils.readPopulation(inputPath);
        System.out.println("Read population: " + population.getPersons().size() + " persons"
                + " | Population lue : " + population.getPersons().size() + " personnes");

        if (!cli.hasFlag("--no-polygon")) {
            GeometryFactory gf = new GeometryFactory();
            Polygon polygon;
            String polygonPath = cli.optional("--polygon", null);
            if (polygonPath != null) {
                polygon = GeoJsonPolygonReader.read(Path.of(polygonPath), gf);
            } else {
                polygon = MontrealBoundary.toPolygon(gf);
                System.out.println("Using built-in Montreal boundary"
                        + " | Utilisation de la frontiere de Montreal integree");
            }
            geofence(population, polygon, gf, sourceCrs);
        }

        if (cli.hasFlag("--no-planless")) {
            removePlanless(population);
        }

        if (cli.hasFlag("--keep-selected-only")) {
            keepSelectedOnly(population);
        }

        if (cli.hasFlag("--no-attr")) {
            stripPersonAttributes(population, Set.of());
        } else {
            String keepAttrRaw = cli.optional("--keep-attr", null);
            if (keepAttrRaw != null) {
                stripPersonAttributes(population,
                        new HashSet<>(Arrays.asList(keepAttrRaw.split(","))));
            }
        }

        if (cli.hasFlag("--no-routes")) {
            stripRoutes(population);
        }

        String sampleStr = cli.optional("--sample", null);
        if (sampleStr != null) {
            double percent = Double.parseDouble(sampleStr);
            if (percent <= 0 || percent > 100) {
                throw new IllegalArgumentException(
                        "--sample must be between 0 and 100"
                                + " | --sample doit etre entre 0 et 100");
            }
            MatsimRandom.reset(4711L);
            PopulationUtils.sampleDown(population, percent / 100.0);
            System.out.println("After sampling " + sampleStr + "%: "
                    + population.getPersons().size() + " persons"
                    + " | Apres echantillonnage " + sampleStr + "% : "
                    + population.getPersons().size() + " personnes");
        }

        if (targetCrs != null && !targetCrs.equals(sourceCrs)) {
            reprojectActivities(population, sourceCrs, targetCrs);
        }

        new PopulationWriter(population).write(outputPath);
        System.out.println("Wrote: " + outputPath + " (" + population.getPersons().size()
                + " persons) | Ecrit : " + outputPath + " ("
                + population.getPersons().size() + " personnes)");
        return 0;
    }

    private void geofence(Population population, Polygon polygon,
                          GeometryFactory gf, String populationCrs) {
        Polygon projected = projectPolygon(polygon, "EPSG:4326", populationCrs, gf);

        List<Person> toRemove = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            if (hasActivityOutside(person, projected, gf)) {
                toRemove.add(person);
            }
        }

        for (Person p : toRemove) {
            population.removePerson(p.getId());
        }

        System.out.println("After geofence: " + population.getPersons().size()
                + " persons (removed " + toRemove.size() + ")"
                + " | Apres geocloture : " + population.getPersons().size()
                + " personnes (retirees " + toRemove.size() + ")");
    }

    private boolean hasActivityOutside(Person person, Polygon polygon, GeometryFactory gf) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) return false;

        for (PlanElement pe : plan.getPlanElements()) {
            if (pe instanceof Activity act) {
                Coord c = act.getCoord();
                if (c == null) continue;
                Point point = gf.createPoint(new Coordinate(c.getX(), c.getY()));
                if (!polygon.contains(point)) return true;
            }
        }
        return false;
    }

    private void removePlanless(Population population) {
        List<Person> toRemove = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            if (person.getPlans().isEmpty()) {
                toRemove.add(person);
                continue;
            }
            boolean allEmpty = true;
            for (Plan plan : person.getPlans()) {
                if (!plan.getPlanElements().isEmpty()) {
                    allEmpty = false;
                    break;
                }
            }
            if (allEmpty) toRemove.add(person);
        }
        for (Person p : toRemove) {
            population.removePerson(p.getId());
        }
        System.out.println("Removed " + toRemove.size() + " planless persons"
                + " | " + toRemove.size() + " personnes sans plan retirees");
    }

    private void keepSelectedOnly(Population population) {
        for (Person person : population.getPersons().values()) {
            Plan selected = person.getSelectedPlan();
            if (selected == null && !person.getPlans().isEmpty()) {
                selected = person.getPlans().get(0);
            }
            List<Plan> toRemove = new ArrayList<>(person.getPlans());
            toRemove.remove(selected);
            for (Plan plan : toRemove) {
                person.removePlan(plan);
            }
        }
    }

    private void stripPersonAttributes(Population population, Set<String> keepSet) {
        for (Person person : population.getPersons().values()) {
            Set<String> toRemove = new HashSet<>(person.getAttributes().getAsMap().keySet());
            toRemove.removeAll(keepSet);
            for (String attr : toRemove) {
                person.getAttributes().removeAttribute(attr);
            }
        }
    }

    private void stripRoutes(Population population) {
        for (Person person : population.getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                for (PlanElement pe : plan.getPlanElements()) {
                    if (pe instanceof Leg leg) {
                        leg.setRoute(null);
                    }
                }
            }
        }
    }

    private void reprojectActivities(Population population, String fromCrs, String toCrs) {
        CoordinateTransformation transform =
                TransformationFactory.getCoordinateTransformation(fromCrs, toCrs);
        for (Person person : population.getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                for (PlanElement pe : plan.getPlanElements()) {
                    if (pe instanceof Activity act && act.getCoord() != null) {
                        act.setCoord(transform.transform(act.getCoord()));
                    }
                }
            }
        }
    }

    private Polygon projectPolygon(Polygon polygon, String fromCrs, String toCrs,
                                   GeometryFactory gf) {
        CoordinateTransformation transform =
                TransformationFactory.getCoordinateTransformation(fromCrs, toCrs);
        Coordinate[] coords = polygon.getExteriorRing().getCoordinates();
        Coordinate[] projected = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            Coord p = transform.transform(new Coord(coords[i].x, coords[i].y));
            projected[i] = new Coordinate(p.getX(), p.getY());
        }
        return gf.createPolygon(projected);
    }
}
