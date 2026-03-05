package ez.backend.turbo.preprocess;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PopulationPreprocessorTest {

    @TempDir
    static Path tempDir;

    static Path populationFile;
    static Path polygonFile;

    // p1-p10: all activities inside polygon, 2 plans each (selected + alt), with attributes
    static final Set<String> INSIDE_PERSONS = Set.of(
            "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10");
    // p11-p15: selected plan has activity outside polygon
    static final Set<String> OUTSIDE_PERSONS = Set.of("p11", "p12", "p13", "p14", "p15");
    // p16-p18: no plans at all
    static final Set<String> PLANLESS_PERSONS = Set.of("p16", "p17", "p18");
    // p19: has a plan but it's empty (no plan elements)
    static final Set<String> EMPTY_PLAN_PERSONS = Set.of("p19");
    // p20: inside, single plan, has attributes
    static final Set<String> SINGLE_PLAN_INSIDE = Set.of("p20");

    static final int TOTAL_PERSONS = 20;
    static final Set<String> PERSON_ATTRS = Set.of("age", "sex", "carAvail", "household_id");

    @BeforeAll
    static void buildFixtures() throws IOException {
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory pf = population.getFactory();

        for (int i = 1; i <= 10; i++) {
            Person person = pf.createPerson(Id.createPersonId("p" + i));
            person.getAttributes().putAttribute("age", 30 + i);
            person.getAttributes().putAttribute("sex", i % 2 == 0 ? 2 : 1);
            person.getAttributes().putAttribute("carAvail", "always");
            person.getAttributes().putAttribute("household_id", (long) (1000 + i));

            Plan selected = pf.createPlan();
            Activity home = pf.createActivityFromCoord("home", new Coord(298000, 5048000));
            home.setEndTime(8 * 3600.0);
            selected.addActivity(home);

            Leg leg = pf.createLeg("car");
            leg.setRoute(RouteUtils.createLinkNetworkRouteImpl(
                    Id.createLinkId("L1"),
                    new Id[]{Id.createLinkId("L3"), Id.createLinkId("L5")},
                    Id.createLinkId("L7")));
            selected.addLeg(leg);

            Activity work = pf.createActivityFromCoord("work", new Coord(302000, 5052000));
            selected.addActivity(work);
            person.addPlan(selected);
            person.setSelectedPlan(selected);

            Plan alt = pf.createPlan();
            Activity h2 = pf.createActivityFromCoord("home", new Coord(298000, 5048000));
            h2.setEndTime(9 * 3600.0);
            alt.addActivity(h2);
            alt.addLeg(pf.createLeg("walk"));
            alt.addActivity(pf.createActivityFromCoord("shop", new Coord(299000, 5049000)));
            person.addPlan(alt);

            population.addPerson(person);
        }

        for (int i = 11; i <= 15; i++) {
            Person person = pf.createPerson(Id.createPersonId("p" + i));
            person.getAttributes().putAttribute("age", 40);
            Plan plan = pf.createPlan();
            Activity home = pf.createActivityFromCoord("home", new Coord(298000, 5048000));
            home.setEndTime(7 * 3600.0);
            plan.addActivity(home);
            plan.addLeg(pf.createLeg("car"));
            plan.addActivity(pf.createActivityFromCoord("work", new Coord(250000, 5000000)));
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            population.addPerson(person);
        }

        for (int i = 16; i <= 18; i++) {
            population.addPerson(pf.createPerson(Id.createPersonId("p" + i)));
        }

        Person p19 = pf.createPerson(Id.createPersonId("p19"));
        p19.addPlan(pf.createPlan());
        population.addPerson(p19);

        Person p20 = pf.createPerson(Id.createPersonId("p20"));
        p20.getAttributes().putAttribute("age", 25);
        p20.getAttributes().putAttribute("sex", 1);
        p20.getAttributes().putAttribute("carAvail", "never");
        p20.getAttributes().putAttribute("household_id", 9999L);
        Plan p20plan = pf.createPlan();
        Activity h20 = pf.createActivityFromCoord("home", new Coord(300000, 5050000));
        h20.setEndTime(8 * 3600.0);
        p20plan.addActivity(h20);
        p20plan.addLeg(pf.createLeg("bike"));
        p20plan.addActivity(pf.createActivityFromCoord("work", new Coord(301000, 5051000)));
        p20.addPlan(p20plan);
        p20.setSelectedPlan(p20plan);
        population.addPerson(p20);

        populationFile = tempDir.resolve("test-population.xml");
        new PopulationWriter(population).write(populationFile.toString());

        polygonFile = tempDir.resolve("polygon.geojson");
        Files.writeString(polygonFile, """
                {"type":"Polygon","coordinates":[[[-74.1,45.3],[-73.3,45.3],[-73.3,45.8],[-74.1,45.8],[-74.1,45.3]]]}""");
    }

    @Test
    void basicReadWritePreservesAllPersons() {
        Path output = tempDir.resolve("out-basic.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());
        assertEquals(TOTAL_PERSONS, result.getPersons().size());
        for (int i = 1; i <= TOTAL_PERSONS; i++) {
            assertNotNull(result.getPersons().get(Id.createPersonId("p" + i)),
                    "Person p" + i + " should be preserved");
        }
    }

    @Test
    void geofenceKeepsInsideRemovesOutside() {
        Path output = tempDir.resolve("out-geofence.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--polygon", polygonFile.toString()});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        for (String pid : INSIDE_PERSONS) {
            assertNotNull(result.getPersons().get(Id.createPersonId(pid)),
                    pid + " has all activities inside polygon, should survive");
        }
        for (String pid : SINGLE_PLAN_INSIDE) {
            assertNotNull(result.getPersons().get(Id.createPersonId(pid)),
                    pid + " has all activities inside polygon, should survive");
        }
        for (String pid : OUTSIDE_PERSONS) {
            assertNull(result.getPersons().get(Id.createPersonId(pid)),
                    pid + " has work activity outside polygon, should be removed");
        }
        // Planless persons have no selected plan → no activities to check → kept by geofence
        for (String pid : PLANLESS_PERSONS) {
            assertNotNull(result.getPersons().get(Id.createPersonId(pid)),
                    pid + " has no plan, geofence skips it (use --no-planless to remove)");
        }
    }

    @Test
    void noPolygonKeepsEveryone() {
        Path output = tempDir.resolve("out-nopoly.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());
        assertEquals(TOTAL_PERSONS, result.getPersons().size());
    }

    @Test
    void noPlanlessRemovesExactlyPlanlessAndEmptyPlan() {
        Path output = tempDir.resolve("out-noplanless.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--no-planless"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        for (String pid : PLANLESS_PERSONS) {
            assertNull(result.getPersons().get(Id.createPersonId(pid)),
                    pid + " has no plans, should be removed by --no-planless");
        }
        for (String pid : EMPTY_PLAN_PERSONS) {
            assertNull(result.getPersons().get(Id.createPersonId(pid)),
                    pid + " has only empty plan, should be removed by --no-planless");
        }
        int expected = TOTAL_PERSONS - PLANLESS_PERSONS.size() - EMPTY_PLAN_PERSONS.size();
        assertEquals(expected, result.getPersons().size());
    }

    @Test
    void keepSelectedOnlyDropsAlternativePlans() {
        Path output = tempDir.resolve("out-selected.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--keep-selected-only"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        for (String pid : INSIDE_PERSONS) {
            Person person = result.getPersons().get(Id.createPersonId(pid));
            assertNotNull(person);
            assertEquals(1, person.getPlans().size(),
                    pid + " had 2 plans, should have 1 after --keep-selected-only");
            Plan plan = person.getPlans().get(0);
            Activity firstAct = (Activity) plan.getPlanElements().get(0);
            assertEquals("home", firstAct.getType());
            assertEquals(8 * 3600.0, firstAct.getEndTime().seconds(), 0.1,
                    pid + " should keep selected plan (end_time=08:00), not alt (end_time=09:00)");
        }
    }

    @Test
    void noAttrStripsAllPersonAttributes() {
        Path output = tempDir.resolve("out-noattr.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--no-attr"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        for (Person person : result.getPersons().values()) {
            assertTrue(person.getAttributes().getAsMap().isEmpty(),
                    person.getId() + " should have zero attributes after --no-attr, has: "
                            + person.getAttributes().getAsMap().keySet());
        }
    }

    @Test
    void keepAttrPreservesOnlyNamed() {
        Path output = tempDir.resolve("out-keepattr.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--keep-attr", "age,sex"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        Person p1 = result.getPersons().get(Id.createPersonId("p1"));
        assertNotNull(p1.getAttributes().getAttribute("age"),
                "age should be kept");
        assertNotNull(p1.getAttributes().getAttribute("sex"),
                "sex should be kept");
        assertNull(p1.getAttributes().getAttribute("carAvail"),
                "carAvail should be stripped (not in --keep-attr)");
        assertNull(p1.getAttributes().getAttribute("household_id"),
                "household_id should be stripped (not in --keep-attr)");
    }

    @Test
    void noRoutesStripsAllRouteData() {
        Path output = tempDir.resolve("out-noroutes.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon", "--no-routes"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        for (Person person : result.getPersons().values()) {
            for (Plan plan : person.getPlans()) {
                for (PlanElement pe : plan.getPlanElements()) {
                    if (pe instanceof Leg leg) {
                        assertNull(leg.getRoute(),
                                person.getId() + " leg should have no route after --no-routes");
                    }
                }
            }
        }

        // Verify routes were present before stripping (p1 has car leg with route)
        Population original = PopulationUtils.readPopulation(populationFile.toString());
        Person origP1 = original.getPersons().get(Id.createPersonId("p1"));
        boolean hadRoute = false;
        for (PlanElement pe : origP1.getSelectedPlan().getPlanElements()) {
            if (pe instanceof Leg leg && leg.getRoute() != null) {
                hadRoute = true;
                break;
            }
        }
        assertTrue(hadRoute, "Original p1 should have had a route to verify stripping works");
    }

    @Test
    void sampleProducesReproducibleSubset() {
        Path output1 = tempDir.resolve("out-sample1.xml");
        Path output2 = tempDir.resolve("out-sample2.xml");

        int code1 = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output1.toString(),
                "--no-polygon", "--sample", "50"});
        int code2 = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output2.toString(),
                "--no-polygon", "--sample", "50"});

        assertEquals(0, code1);
        assertEquals(0, code2);

        Population result1 = PopulationUtils.readPopulation(output1.toString());
        Population result2 = PopulationUtils.readPopulation(output2.toString());

        assertTrue(result1.getPersons().size() > 0, "Sample should keep some persons");
        assertTrue(result1.getPersons().size() < TOTAL_PERSONS, "Sample should remove some persons");
        int tolerance = (int) (TOTAL_PERSONS * 0.3);
        int expected = TOTAL_PERSONS / 2;
        assertTrue(Math.abs(result1.getPersons().size() - expected) <= tolerance,
                "50% sample of " + TOTAL_PERSONS + " should be ~" + expected
                        + " (±" + tolerance + "), got " + result1.getPersons().size());

        assertEquals(result1.getPersons().size(), result2.getPersons().size(),
                "Same seed should produce same sample size");
        for (Id<Person> id : result1.getPersons().keySet()) {
            assertNotNull(result2.getPersons().get(id),
                    "Same seed should produce identical person set, missing: " + id);
        }
    }

    @Test
    void combinedGeofenceAndPlanlessAndSelectedOnly() {
        Path output = tempDir.resolve("out-combined.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--polygon", polygonFile.toString(),
                "--no-planless", "--keep-selected-only"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        // Geofence removes p11-p15, --no-planless removes p16-p19
        // Remaining: p1-p10 and p20 = 11 persons
        assertEquals(INSIDE_PERSONS.size() + SINGLE_PLAN_INSIDE.size(),
                result.getPersons().size());

        for (Person person : result.getPersons().values()) {
            assertEquals(1, person.getPlans().size(),
                    person.getId() + " should have exactly 1 plan after --keep-selected-only");
        }
    }

    @Test
    void activityCoordinatesPreservedExactly() {
        Path output = tempDir.resolve("out-coords.xml");
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString(),
                "--output", output.toString(),
                "--no-polygon"});
        assertEquals(0, code);
        Population result = PopulationUtils.readPopulation(output.toString());

        Person p1 = result.getPersons().get(Id.createPersonId("p1"));
        Activity home = (Activity) p1.getSelectedPlan().getPlanElements().get(0);
        assertEquals(298000.0, home.getCoord().getX(), 0.1);
        assertEquals(5048000.0, home.getCoord().getY(), 0.1);
        assertEquals(8 * 3600.0, home.getEndTime().seconds(), 0.1);
        assertEquals("home", home.getType());

        Activity work = (Activity) p1.getSelectedPlan().getPlanElements().get(2);
        assertEquals(302000.0, work.getCoord().getX(), 0.1);
        assertEquals(5052000.0, work.getCoord().getY(), 0.1);
        assertEquals("work", work.getType());
    }

    @Test
    void missingInputReturnsError() {
        int code = new PopulationPreprocessor().execute(new String[]{
                "--output", tempDir.resolve("missing.xml").toString()});
        assertEquals(1, code);
    }

    @Test
    void missingOutputReturnsError() {
        int code = new PopulationPreprocessor().execute(new String[]{
                "--input", populationFile.toString()});
        assertEquals(1, code);
    }
}
