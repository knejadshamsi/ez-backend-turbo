package ez.backend.turbo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(OrderAnnotation.class)
class EndToEndIT {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE = "http://localhost:8080";
    private static final int HEALTH_TIMEOUT_SEC = 30;
    private static final int SIMULATION_TIMEOUT_MS = 300_000;
    private static final int REPLAY_TIMEOUT_MS = 30_000;
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir"));
    private static final Path DEV_DATA = PROJECT_DIR.resolve("dev-data");

    private static Process serverProcess;
    private static List<Map<String, Object>> liveMessages;
    private static String requestId;

    @BeforeAll
    static void startServer() throws Exception {
        cleanDevArtifacts();

        String jar = PROJECT_DIR.resolve("target/turbo-1.0.0-SNAPSHOT.jar").toString();
        assertTrue(Files.isRegularFile(Path.of(jar)), "JAR not found — run mvn package first");

        ProcessBuilder pb = new ProcessBuilder("java", "-jar", jar, "--dev");
        pb.directory(PROJECT_DIR.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(PROJECT_DIR.resolve("dev-test-output.log").toString()));
        serverProcess = pb.start();

        waitForHealth();
    }

    @AfterAll
    static void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroyForcibly();
        }
    }

    @Test
    @Order(1)
    void testLiveSimulation() throws Exception {
        String requestBody = Files.readString(DEV_DATA.resolve("dev-request.json"));
        liveMessages = sendSseRequest("POST", BASE + "/simulate", requestBody, SIMULATION_TIMEOUT_MS);

        assertFalse(liveMessages.isEmpty(), "No SSE messages received");

        List<String> types = liveMessages.stream().map(m -> (String) m.get("messageType")).toList();
        assertEquals("pa_request_accepted", types.getFirst());
        assertEquals("success_process", types.getLast());
        assertTrue(types.contains("pa_simulation_start"));

        Map<String, Object> accepted = findPayload("pa_request_accepted");
        requestId = (String) accepted.get("requestId");
        assertNotNull(requestId);
        assertDoesNotThrow(() -> UUID.fromString(requestId));

        Map<String, Object> overview = findPayload("data_text_overview");
        assertPositiveNumber(overview, "personCount");
        assertPositiveNumber(overview, "legCount");
        assertPositiveNumber(overview, "totalKmTraveled");
        assertPositiveNumber(overview, "networkNodes");
        assertPositiveNumber(overview, "networkLinks");
        assertPositiveNumber(overview, "simulationAreaKm2");
        assertPositiveNumber(overview, "samplePersonCount");
        assertPositiveNumber(overview, "sampleLegCount");
        assertPositiveNumber(overview, "sampleTotalKmTraveled");
        assertPositiveNumber(overview, "samplePercentage");

        Map<String, Object> emP1 = findPayload("data_text_paragraph1_emissions");
        assertPositiveNumber(emP1, "co2Baseline");
        assertPositiveNumber(emP1, "co2Policy");
        assertNotNaN(emP1, "co2DeltaPercent");
        assertPositiveNumber(emP1, "noxBaseline");
        assertPositiveNumber(emP1, "noxPolicy");
        assertNotNaN(emP1, "noxDeltaPercent");
        assertPositiveNumber(emP1, "pm25Baseline");
        assertPositiveNumber(emP1, "pm25Policy");
        assertNotNaN(emP1, "pm25DeltaPercent");
        assertPositiveNumber(emP1, "pm10Baseline");
        assertPositiveNumber(emP1, "pm10Policy");
        assertNotNaN(emP1, "pm10DeltaPercent");
        assertNonNegativeNumber(emP1, "privateCo2Baseline");
        assertNonNegativeNumber(emP1, "privateCo2Policy");
        assertNotNaN(emP1, "privateCo2DeltaPercent");
        assertPositiveNumber(emP1, "transitCo2Baseline");
        assertPositiveNumber(emP1, "transitCo2Policy");
        assertNonNegativeNumber(emP1, "privateNoxBaseline");
        assertNonNegativeNumber(emP1, "privateNoxPolicy");
        assertNotNaN(emP1, "privateNoxDeltaPercent");
        assertPositiveNumber(emP1, "transitNoxBaseline");
        assertPositiveNumber(emP1, "transitNoxPolicy");
        assertNonNegativeNumber(emP1, "privatePm25Baseline");
        assertNonNegativeNumber(emP1, "privatePm25Policy");
        assertNotNaN(emP1, "privatePm25DeltaPercent");
        assertNonNegativeNumber(emP1, "privatePm10Baseline");
        assertNonNegativeNumber(emP1, "privatePm10Policy");
        assertNotNaN(emP1, "privatePm10DeltaPercent");

        Map<String, Object> barChart = findPayload("data_chart_bar_emissions");
        assertPositiveNumber(barChart, "co2Baseline");
        assertPositiveNumber(barChart, "co2Policy");

        Map<String, Object> lineChart = findPayload("data_chart_line_emissions");
        assertListSize(lineChart, "timeBins", 12);
        assertListSize(lineChart, "co2Baseline", 12);
        assertListSize(lineChart, "co2Policy", 12);
        assertListSize(lineChart, "noxBaseline", 12);
        assertListSize(lineChart, "noxPolicy", 12);
        assertListSize(lineChart, "pm25Baseline", 12);
        assertListSize(lineChart, "pm25Policy", 12);
        assertListSize(lineChart, "pm10Baseline", 12);
        assertListSize(lineChart, "pm10Policy", 12);

        @SuppressWarnings("unchecked")
        Map<String, Object> stackedBar = findPayload("data_chart_stacked_bar_emissions");
        @SuppressWarnings("unchecked")
        Map<String, Object> stackedBaseline = (Map<String, Object>) stackedBar.get("baseline");
        assertNotNull(stackedBaseline, "stacked bar baseline is null");
        @SuppressWarnings("unchecked")
        Map<String, Object> stackedPolicy = (Map<String, Object>) stackedBar.get("policy");
        assertNotNull(stackedPolicy, "stacked bar policy is null");
        @SuppressWarnings("unchecked")
        Map<String, Object> stackedPrivate = (Map<String, Object>) stackedBaseline.get("private");
        assertNotNull(stackedPrivate, "stacked bar private is null");
        @SuppressWarnings("unchecked")
        Map<String, Object> co2ByType = (Map<String, Object>) stackedPrivate.get("co2ByType");
        assertNotNull(co2ByType, "co2ByType is null");
        assertFalse(co2ByType.isEmpty(), "co2ByType is empty");
        assertNotNull(stackedBaseline.get("transit"), "transit is null");

        Map<String, Object> emP2 = findPayload("data_text_paragraph2_emissions");
        assertNonNegativeNumber(emP2, "pm25PerKm2Baseline");
        assertNonNegativeNumber(emP2, "pm25PerKm2Policy");
        assertPositiveNumber(emP2, "zoneAreaKm2");
        assertPositiveNumber(emP2, "mixingHeightMeters");

        @SuppressWarnings("unchecked")
        Map<String, Object> warmColdIntensity = findPayload("data_warm_cold_intensity_emissions");
        @SuppressWarnings("unchecked")
        Map<String, Object> warmCold = (Map<String, Object>) warmColdIntensity.get("warmCold");
        assertNotNull(warmCold, "warmCold is null");
        assertPositiveNumber(warmCold, "warmBaseline");
        assertPositiveNumber(warmCold, "warmPolicy");
        assertNonNegativeNumber(warmCold, "coldBaseline");
        assertNonNegativeNumber(warmCold, "coldPolicy");
        @SuppressWarnings("unchecked")
        Map<String, Object> intensity = (Map<String, Object>) warmColdIntensity.get("intensity");
        assertNotNull(intensity, "intensity is null");
        assertPositiveNumber(intensity, "co2Baseline");
        assertPositiveNumber(intensity, "co2Policy");
        assertPositiveNumber(intensity, "distanceBaseline");
        assertPositiveNumber(intensity, "distancePolicy");
        assertPositiveNumber(intensity, "co2PerMeterBaseline");
        assertPositiveNumber(intensity, "co2PerMeterPolicy");

        assertHasMapSignal("emissions");

        Map<String, Object> prParagraph = findPayload("data_text_paragraph1_people_response");
        assertPositiveNumber(prParagraph, "totalTrips");
        assertNonNegativeNumber(prParagraph, "affectedTrips");
        assertNonNegativeNumber(prParagraph, "affectedAgents");
        assertNonNegativeNumber(prParagraph, "modeShiftCount");
        assertNotNaN(prParagraph, "modeShiftPct");
        assertNonNegativeNumber(prParagraph, "reroutedCount");
        assertNotNaN(prParagraph, "reroutedPct");
        assertNonNegativeNumber(prParagraph, "paidPenaltyCount");
        assertNotNaN(prParagraph, "paidPenaltyPct");
        assertNonNegativeNumber(prParagraph, "cancelledCount");
        assertNotNaN(prParagraph, "cancelledPct");
        assertNonNegativeNumber(prParagraph, "noChangeCount");
        assertNotNaN(prParagraph, "noChangePct");
        assertNonEmptyString(prParagraph, "dominantResponse");
        assertNotNull(prParagraph.get("penaltyCharges"), "penaltyCharges is null");
        assertInstanceOf(List.class, prParagraph.get("penaltyCharges"));
        int prTotal = ((Number) prParagraph.get("totalTrips")).intValue();
        int prAffected = ((Number) prParagraph.get("affectedTrips")).intValue();
        int prNoChange = ((Number) prParagraph.get("noChangeCount")).intValue();
        assertEquals(prTotal, prAffected + prNoChange,
                "affectedTrips + noChangeCount should equal totalTrips");

        @SuppressWarnings("unchecked")
        Map<String, Object> sankey = findPayload("data_chart_sankey_people_response");
        assertListSize(sankey, "nodes", 5);
        assertNonEmptyList(sankey, "flows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows = (List<Map<String, Object>>) sankey.get("flows");
        for (Map<String, Object> flow : flows) {
            assertNonEmptyString(flow, "from");
            assertNonEmptyString(flow, "to");
            assertPositiveNumber(flow, "count");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> prBar = findPayload("data_chart_bar_people_response");
        assertListSize(prBar, "modes", 5);
        assertListSize(prBar, "baseline", 5);
        assertListSize(prBar, "policy", 5);
        @SuppressWarnings("unchecked")
        List<Number> barBaseline = (List<Number>) prBar.get("baseline");
        @SuppressWarnings("unchecked")
        List<Number> barPolicy = (List<Number>) prBar.get("policy");
        double barBaseSum = barBaseline.stream().mapToDouble(Number::doubleValue).sum();
        double barPolSum = barPolicy.stream().mapToDouble(Number::doubleValue).sum();
        assertTrue(Math.abs(barBaseSum - 100.0) < 0.1,
                "bar baseline percentages should sum to ~100 but was " + barBaseSum);
        assertTrue(Math.abs(barPolSum - 100.0) < 0.1,
                "bar policy percentages should sum to ~100 but was " + barPolSum);

        assertHasMapSignal("people_response");

        Map<String, Object> tripParagraph = findPayload("data_text_paragraph1_trip_legs");
        assertPositiveNumber(tripParagraph, "totalTrips");
        assertNonNegativeNumber(tripParagraph, "changedTrips");
        assertNonNegativeNumber(tripParagraph, "unchangedTrips");
        assertNonNegativeNumber(tripParagraph, "cancelledTrips");
        assertNonNegativeNumber(tripParagraph, "newTrips");
        assertNonNegativeNumber(tripParagraph, "modeShiftTrips");
        assertNotNaN(tripParagraph, "netCo2DeltaGrams");
        assertNotNaN(tripParagraph, "netTimeDeltaMinutes");
        assertNotNaN(tripParagraph, "avgCo2DeltaGrams");
        assertNotNaN(tripParagraph, "avgTimeDeltaMinutes");
        assertNonNegativeNumber(tripParagraph, "winWinCount");
        assertNonNegativeNumber(tripParagraph, "loseLoseCount");
        assertNonNegativeNumber(tripParagraph, "envWinPersonalCostCount");
        assertNonNegativeNumber(tripParagraph, "personalWinEnvCostCount");
        assertNonEmptyString(tripParagraph, "dominantOutcome");
        int tpTotal = ((Number) tripParagraph.get("totalTrips")).intValue();
        int tpChanged = ((Number) tripParagraph.get("changedTrips")).intValue();
        int tpUnchanged = ((Number) tripParagraph.get("unchangedTrips")).intValue();
        assertEquals(tpTotal, tpChanged + tpUnchanged,
                "changedTrips + unchangedTrips should equal totalTrips");

        Map<String, Object> tripLegs = findPayload("data_table_trip_legs");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) tripLegs.get("records");
        assertNotNull(records, "trip records is null");
        assertFalse(records.isEmpty(), "trip records should not be empty");
        int totalRecords = ((Number) tripLegs.get("totalRecords")).intValue();
        int totalAllRecords = ((Number) tripLegs.get("totalAllRecords")).intValue();
        assertTrue(totalRecords > 0, "totalRecords should be > 0");
        assertTrue(totalAllRecords >= totalRecords,
                "totalAllRecords should be >= totalRecords");
        assertEquals(10, ((Number) tripLegs.get("pageSize")).intValue());
        for (Map<String, Object> rec : records) {
            assertNonEmptyString(rec, "legId");
            assertNonEmptyString(rec, "personId");
            assertNonEmptyString(rec, "originActivity");
            assertNonEmptyString(rec, "destinationActivity");
            assertNotNaN(rec, "co2DeltaGrams");
            assertNotNaN(rec, "timeDeltaMinutes");
            assertNonEmptyString(rec, "impact");
        }

        assertHasMapSignal("trip_legs");
    }

    @Test
    @Order(2)
    void testReplaySimulation() throws Exception {
        assertNotNull(requestId, "requestId not set — testLiveSimulation must run first");

        String sessionJson = "{\"testKey\":\"testValue\",\"colorPalette\":[\"#FF0000\"]}";

        Thread.sleep(200);
        HttpURLConnection sessionConn = sendPostJson(
                BASE + "/scenario/" + requestId + "/session-data", sessionJson);
        assertEquals(200, sessionConn.getResponseCode());
        Map<String, Object> sessionResponse = readJsonResponse(sessionConn);
        assertEquals(200, ((Number) sessionResponse.get("statusCode")).intValue());
        assertNotNull(sessionResponse.get("message"));
        sessionConn.disconnect();

        Thread.sleep(200);
        List<Map<String, Object>> replayMessages = sendSseRequest(
                "GET", BASE + "/scenario/" + requestId, null, REPLAY_TIMEOUT_MS);

        assertTrue(replayMessages.size() >= 4, "Expected at least 4 messages (preamble + output)");

        assertEquals("scenario_status", replayMessages.get(0).get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> statusPayload = (Map<String, Object>) replayMessages.get(0).get("payload");
        assertEquals("COMPLETED", statusPayload.get("status"));

        assertEquals("scenario_input", replayMessages.get(1).get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputPayload = (Map<String, Object>) replayMessages.get(1).get("payload");
        assertNotNull(inputPayload, "scenario_input payload is null");
        assertNotNull(inputPayload.get("zones"), "scenario_input should contain zones");
        assertInstanceOf(List.class, inputPayload.get("zones"));
        assertFalse(((List<?>) inputPayload.get("zones")).isEmpty(), "zones should not be empty");

        assertEquals("scenario_session", replayMessages.get(2).get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionPayload = (Map<String, Object>) replayMessages.get(2).get("payload");
        var expectedSession = mapper.readTree(sessionJson);
        var actualSession = mapper.valueToTree(sessionPayload);
        assertEquals(expectedSession, actualSession, "Session data round-trip mismatch");

        List<Map<String, Object>> outputMessages = replayMessages.subList(3, replayMessages.size());

        List<String> dataTypes = List.of(
                "data_text_overview",
                "data_text_paragraph1_emissions", "data_text_paragraph2_emissions",
                "data_chart_bar_emissions", "data_chart_line_emissions",
                "data_chart_stacked_bar_emissions", "data_warm_cold_intensity_emissions",
                "data_text_paragraph1_people_response",
                "data_chart_sankey_people_response", "data_chart_bar_people_response",
                "data_text_paragraph1_trip_legs", "data_table_trip_legs");

        for (String dataType : dataTypes) {
            Map<String, Object> liveMsg = liveMessages.stream()
                    .filter(m -> dataType.equals(m.get("messageType")))
                    .findFirst().orElse(null);
            Map<String, Object> replayMsg = outputMessages.stream()
                    .filter(m -> dataType.equals(m.get("messageType")))
                    .findFirst().orElse(null);

            if (liveMsg != null) {
                assertNotNull(replayMsg, "Replay missing message type: " + dataType);
                var liveTree = mapper.valueToTree(liveMsg.get("payload"));
                var replayTree = mapper.valueToTree(replayMsg.get("payload"));
                assertEquals(liveTree, replayTree,
                        "Payload mismatch for " + dataType);
            }
        }
    }

    @Test
    @Order(3)
    void testRestMapEndpoints() throws Exception {
        assertNotNull(requestId, "requestId not set");

        String[][] maps = {
                {"emissions", "success_map_emissions", "error_map_emissions"},
                {"people-response", "success_map_people_response", "error_map_people_response"},
                {"trip-legs", "success_map_trip_legs", "error_map_trip_legs"}
        };

        for (String[] mapDef : maps) {
            String path = mapDef[0];
            String successType = mapDef[1];
            boolean mapSuccess = liveMessages.stream()
                    .anyMatch(m -> successType.equals(m.get("messageType")));

            HttpURLConnection conn = openGet(BASE + "/scenario/" + requestId + "/maps/" + path);
            if (mapSuccess) {
                assertEquals(200, conn.getResponseCode(), path + " map should return 200");
                Map<String, Object> body = readJsonResponse(conn);
                assertEquals(200, ((Number) body.get("statusCode")).intValue());
                assertNotNull(body.get("payload"), path + " map payload is null");
            } else {
                assertEquals(404, conn.getResponseCode(), path + " map should return 404");
            }
            conn.disconnect();
        }
    }

    @Test
    @Order(4)
    void testRestTripLegs() throws Exception {
        assertNotNull(requestId, "requestId not set");

        HttpURLConnection conn = openGet(
                BASE + "/scenario/" + requestId + "/trip-legs?page=1&pageSize=50");
        assertEquals(200, conn.getResponseCode());
        Map<String, Object> body = readJsonResponse(conn);
        conn.disconnect();

        assertEquals(200, ((Number) body.get("statusCode")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        @SuppressWarnings("unchecked")
        List<?> records = (List<?>) payload.get("records");
        assertNotNull(records, "records is null");
        int totalRecords = ((Number) payload.get("totalRecords")).intValue();
        int totalAllRecords = ((Number) payload.get("totalAllRecords")).intValue();
        assertTrue(totalRecords >= 0);
        assertTrue(totalAllRecords >= totalRecords,
                "totalAllRecords should be >= totalRecords");
        assertEquals(1, ((Number) payload.get("page")).intValue());
        assertEquals(50, ((Number) payload.get("pageSize")).intValue());

        HttpURLConnection conn2 = openGet(
                BASE + "/scenario/" + requestId + "/trip-legs?page=1&pageSize=10");
        assertEquals(200, conn2.getResponseCode());
        Map<String, Object> body2 = readJsonResponse(conn2);
        conn2.disconnect();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload2 = (Map<String, Object>) body2.get("payload");
        @SuppressWarnings("unchecked")
        List<?> records2 = (List<?>) payload2.get("records");
        assertTrue(records2.size() <= 10, "pageSize=10 should return at most 10 records");

        HttpURLConnection conn3 = openGet(
                BASE + "/scenario/" + requestId + "/trip-legs?page=9999&pageSize=50");
        assertEquals(200, conn3.getResponseCode());
        Map<String, Object> body3 = readJsonResponse(conn3);
        conn3.disconnect();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload3 = (Map<String, Object>) body3.get("payload");
        @SuppressWarnings("unchecked")
        List<?> records3 = (List<?>) payload3.get("records");
        assertTrue(records3.isEmpty(), "page 9999 should return empty records");
    }

    @Test
    @Order(6)
    void testRetryComponents() throws Exception {
        assertNotNull(requestId, "requestId not set");

        String[] retryTypes = {
                "text_overview",
                "text_paragraph1_emissions",
                "text_paragraph2_emissions",
                "chart_bar_emissions",
                "chart_line_emissions",
                "chart_stacked_bar_emissions",
                "warm_cold_intensity_emissions",
                "text_paragraph1_people_response",
                "chart_sankey_people_response",
                "chart_bar_people_response",
                "text_paragraph1_trip_legs"
        };

        for (String retryType : retryTypes) {
            Thread.sleep(100);
            String sseType = "data_" + retryType;

            Map<String, Object> liveMsg = liveMessages.stream()
                    .filter(m -> sseType.equals(m.get("messageType")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No live SSE message for " + sseType));

            HttpURLConnection conn = sendPostJson(
                    BASE + "/scenario/" + requestId + "/retry",
                    "{\"messageType\":\"" + retryType + "\"}");
            assertEquals(200, conn.getResponseCode(), "Retry failed for " + retryType);
            Map<String, Object> body = readJsonResponse(conn);
            conn.disconnect();

            assertEquals(200, ((Number) body.get("statusCode")).intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) body.get("payload");
            assertEquals(sseType, envelope.get("messageType"),
                    "Retry messageType mismatch for " + retryType);

            var liveTree = mapper.valueToTree(liveMsg.get("payload"));
            var retryTree = mapper.valueToTree(envelope.get("payload"));
            assertEquals(liveTree, retryTree,
                    "Retry payload mismatch for " + retryType);
        }
    }

    @Test
    @Order(7)
    void testRetryGuards() throws Exception {
        assertNotNull(requestId, "requestId not set");
        Thread.sleep(200);

        HttpURLConnection invalid = sendPostJson(
                BASE + "/scenario/" + requestId + "/retry",
                "{\"messageType\":\"not_a_real_type\"}");
        assertEquals(400, invalid.getResponseCode());
        Map<String, Object> invalidBody = readErrorJsonResponse(invalid);
        assertEquals(400, ((Number) invalidBody.get("statusCode")).intValue());
        invalid.disconnect();

        Thread.sleep(100);
        HttpURLConnection missing = sendPostJson(
                BASE + "/scenario/" + requestId + "/retry", "{}");
        assertEquals(400, missing.getResponseCode());
        missing.disconnect();

        Thread.sleep(100);
        HttpURLConnection notFound = sendPostJson(
                BASE + "/scenario/00000000-0000-0000-0000-000000000000/retry",
                "{\"messageType\":\"text_overview\"}");
        assertEquals(404, notFound.getResponseCode());
        notFound.disconnect();

        Thread.sleep(100);
        HttpURLConnection badUuid = sendPostJson(
                BASE + "/scenario/not-a-uuid/retry",
                "{\"messageType\":\"text_overview\"}");
        assertEquals(400, badUuid.getResponseCode());
        badUuid.disconnect();
    }

    @Test
    @Order(8)
    void testCancelCompletedScenario() throws Exception {
        assertNotNull(requestId, "requestId not set");
        Thread.sleep(200);

        HttpURLConnection conn = sendPostJson(
                BASE + "/scenario/" + requestId + "/cancel", null);
        assertEquals(409, conn.getResponseCode());
        Map<String, Object> body = readErrorJsonResponse(conn);
        assertEquals(409, ((Number) body.get("statusCode")).intValue());
        conn.disconnect();
    }

    @Test
    @Order(9)
    void testDeleteScenario() throws Exception {
        assertNotNull(requestId, "requestId not set");
        Thread.sleep(200);

        HttpURLConnection conn = sendDelete(BASE + "/scenario/" + requestId);
        assertEquals(200, conn.getResponseCode());
        Map<String, Object> body = readJsonResponse(conn);
        assertEquals(200, ((Number) body.get("statusCode")).intValue());
        conn.disconnect();

        Path outputDir = DEV_DATA.resolve("output").resolve(requestId);
        assertFalse(Files.isDirectory(outputDir), "Output directory should be deleted");

        Thread.sleep(200);
        List<Map<String, Object>> refetchMessages = sendSseRequest(
                "GET", BASE + "/scenario/" + requestId, null, REPLAY_TIMEOUT_MS);
        assertEquals(1, refetchMessages.size(), "Deleted refetch should send scenario_status only");
        assertEquals("scenario_status", refetchMessages.get(0).get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> deletedStatus = (Map<String, Object>) refetchMessages.get(0).get("payload");
        assertEquals("DELETED", deletedStatus.get("status"));

        Thread.sleep(100);
        HttpURLConnection tripLegs = openGet(
                BASE + "/scenario/" + requestId + "/trip-legs?page=1&pageSize=50");
        assertEquals(409, tripLegs.getResponseCode());
        tripLegs.disconnect();

        Thread.sleep(100);
        HttpURLConnection mapConn = openGet(
                BASE + "/scenario/" + requestId + "/maps/emissions");
        assertEquals(409, mapConn.getResponseCode());
        mapConn.disconnect();

        Thread.sleep(100);
        HttpURLConnection retryConn = sendPostJson(
                BASE + "/scenario/" + requestId + "/retry",
                "{\"messageType\":\"text_overview\"}");
        assertEquals(400, retryConn.getResponseCode());
        retryConn.disconnect();

        Thread.sleep(100);
        HttpURLConnection cancelConn = sendPostJson(
                BASE + "/scenario/" + requestId + "/cancel", null);
        assertEquals(409, cancelConn.getResponseCode());
        cancelConn.disconnect();

        Thread.sleep(100);
        HttpURLConnection deleteAgain = sendDelete(BASE + "/scenario/" + requestId);
        assertEquals(400, deleteAgain.getResponseCode());
        Map<String, Object> deleteAgainBody = readErrorJsonResponse(deleteAgain);
        assertEquals(400, ((Number) deleteAgainBody.get("statusCode")).intValue());
        deleteAgain.disconnect();
    }

    @Test
    @Order(10)
    void testErrorCasesNewEndpoints() throws Exception {
        Thread.sleep(200);

        HttpURLConnection cancelBad = sendPostJson(
                BASE + "/scenario/not-a-uuid/cancel", null);
        assertEquals(400, cancelBad.getResponseCode());
        cancelBad.disconnect();

        Thread.sleep(100);
        HttpURLConnection cancelNotFound = sendPostJson(
                BASE + "/scenario/00000000-0000-0000-0000-000000000000/cancel", null);
        assertEquals(404, cancelNotFound.getResponseCode());
        cancelNotFound.disconnect();

        Thread.sleep(100);
        HttpURLConnection deleteBad = sendDelete(BASE + "/scenario/not-a-uuid");
        assertEquals(400, deleteBad.getResponseCode());
        deleteBad.disconnect();

        Thread.sleep(100);
        HttpURLConnection deleteNotFound = sendDelete(
                BASE + "/scenario/00000000-0000-0000-0000-000000000000");
        assertEquals(404, deleteNotFound.getResponseCode());
        deleteNotFound.disconnect();
    }

    @Test
    @Order(12)
    @SuppressWarnings("unchecked")
    void testDraftCrud() throws Exception {
        Thread.sleep(200);

        String requestBody = Files.readString(DEV_DATA.resolve("dev-request.json"));
        Object inputObj = mapper.readValue(requestBody, Object.class);
        String sessionJson = "{" +
                "\"zoneSessionData\":{\"4ea608bb-1066-4d22-a3b5-15f7505a3f31\":" +
                "{\"name\":\"Downtown Zone\",\"color\":\"#E53935\",\"hidden\":false," +
                "\"description\":\"Central business district\",\"scale\":[500,\"m\"]}}," +
                "\"simulationAreaDisplay\":{\"borderStyle\":\"solid\",\"fillOpacity\":0.3}," +
                "\"carDistributionCategories\":{\"zeroEmission\":true,\"nearZeroEmission\":true," +
                "\"lowEmission\":true,\"midEmission\":true,\"highEmission\":true}," +
                "\"customAreaSessionData\":{\"b2c3d4e5-6789-4abc-def0-123456789abc\":" +
                "{\"name\":\"Study Area\",\"color\":\"#1E88E5\"}}," +
                "\"scaledAreaSessionData\":{\"c3d4e5f6-789a-4bcd-ef01-23456789abcd\":" +
                "{\"scale\":[1000,\"m\"],\"color\":\"#43A047\"}}," +
                "\"activeZone\":\"4ea608bb-1066-4d22-a3b5-15f7505a3f31\"," +
                "\"activeCustomArea\":null," +
                "\"colorPalette\":[\"#E53935\",\"#1E88E5\",\"#43A047\"]}";
        Map<String, Object> createMap = new LinkedHashMap<>();
        createMap.put("inputData", inputObj);
        createMap.put("sessionData", mapper.readValue(sessionJson, Object.class));
        String createBody = mapper.writeValueAsString(createMap);

        HttpURLConnection createConn = sendPostJson(BASE + "/draft", createBody);
        assertEquals(200, createConn.getResponseCode());
        Map<String, Object> createResp = readJsonResponse(createConn);
        Map<String, Object> createPayload = (Map<String, Object>) createResp.get("payload");
        String draftId = (String) createPayload.get("draftId");
        assertNotNull(draftId);
        assertTrue(draftId.startsWith("d_"), "Draft ID should start with d_");
        createConn.disconnect();

        Thread.sleep(100);
        HttpURLConnection getConn = openGet(BASE + "/draft/" + draftId);
        assertEquals(200, getConn.getResponseCode());
        Map<String, Object> getResp = readJsonResponse(getConn);
        Map<String, Object> getPayload = (Map<String, Object>) getResp.get("payload");
        assertEquals(draftId, getPayload.get("draftId"));
        var expectedInput = mapper.valueToTree(inputObj);
        var actualInput = mapper.valueToTree(getPayload.get("inputData"));
        assertEquals(expectedInput, actualInput, "Input data round-trip mismatch");
        var expectedSession = mapper.readTree(sessionJson);
        var actualSession = mapper.valueToTree(getPayload.get("sessionData"));
        assertEquals(expectedSession, actualSession, "Session data round-trip mismatch");
        assertNotNull(getPayload.get("createdAt"));
        getConn.disconnect();

        Thread.sleep(100);
        String updatedSessionJson = "{" +
                "\"zoneSessionData\":{\"4ea608bb-1066-4d22-a3b5-15f7505a3f31\":" +
                "{\"name\":\"Renamed Zone\",\"color\":\"#FF8F00\",\"hidden\":true," +
                "\"scale\":[1000,\"m\"]}}," +
                "\"simulationAreaDisplay\":{\"borderStyle\":\"dashed\",\"fillOpacity\":0.5}," +
                "\"carDistributionCategories\":{\"zeroEmission\":true,\"nearZeroEmission\":false," +
                "\"lowEmission\":true,\"midEmission\":false,\"highEmission\":true}," +
                "\"customAreaSessionData\":{}," +
                "\"scaledAreaSessionData\":{}," +
                "\"activeZone\":null," +
                "\"activeCustomArea\":null," +
                "\"colorPalette\":[\"#FF8F00\"]}";
        Map<String, Object> updateMap = new LinkedHashMap<>();
        updateMap.put("inputData", inputObj);
        updateMap.put("sessionData", mapper.readValue(updatedSessionJson, Object.class));
        String updateBody = mapper.writeValueAsString(updateMap);
        HttpURLConnection putConn = sendPutJson(BASE + "/draft/" + draftId, updateBody);
        assertEquals(200, putConn.getResponseCode());
        putConn.disconnect();

        Thread.sleep(100);
        HttpURLConnection getUpdated = openGet(BASE + "/draft/" + draftId);
        assertEquals(200, getUpdated.getResponseCode());
        Map<String, Object> updatedResp = readJsonResponse(getUpdated);
        Map<String, Object> updatedPayload = (Map<String, Object>) updatedResp.get("payload");
        var updatedExpectedSession = mapper.readTree(updatedSessionJson);
        var updatedActualSession = mapper.valueToTree(updatedPayload.get("sessionData"));
        assertEquals(updatedExpectedSession, updatedActualSession, "Updated session round-trip mismatch");
        getUpdated.disconnect();

        Thread.sleep(100);
        HttpURLConnection deleteConn = sendDelete(BASE + "/draft/" + draftId);
        assertEquals(200, deleteConn.getResponseCode());
        deleteConn.disconnect();

        Thread.sleep(100);
        HttpURLConnection getDeleted = openGet(BASE + "/draft/" + draftId);
        assertEquals(404, getDeleted.getResponseCode());
        getDeleted.disconnect();
    }

    @Test
    @Order(13)
    void testDraftGuards() throws Exception {
        Thread.sleep(200);

        HttpURLConnection noPrefix = openGet(BASE + "/draft/00000000-0000-0000-0000-000000000000");
        assertEquals(400, noPrefix.getResponseCode());
        noPrefix.disconnect();

        Thread.sleep(100);
        HttpURLConnection badUuid = openGet(BASE + "/draft/d_not-a-uuid");
        assertEquals(400, badUuid.getResponseCode());
        badUuid.disconnect();

        Thread.sleep(100);
        HttpURLConnection notFound = openGet(
                BASE + "/draft/d_00000000-0000-0000-0000-000000000000");
        assertEquals(404, notFound.getResponseCode());
        notFound.disconnect();

        Thread.sleep(100);
        HttpURLConnection deleteNotFound = sendDelete(
                BASE + "/draft/d_00000000-0000-0000-0000-000000000000");
        assertEquals(404, deleteNotFound.getResponseCode());
        deleteNotFound.disconnect();

        Thread.sleep(100);
        HttpURLConnection putNotFound = sendPutJson(
                BASE + "/draft/d_00000000-0000-0000-0000-000000000000",
                "{\"inputData\":{}}");
        assertEquals(404, putNotFound.getResponseCode());
        putNotFound.disconnect();
    }

    @Test
    @Order(14)
    @SuppressWarnings("unchecked")
    void testScenarioStatus() throws Exception {
        Thread.sleep(200);

        HttpURLConnection statusConn = openGet(
                BASE + "/scenario/" + requestId + "/status");
        assertEquals(200, statusConn.getResponseCode());
        Map<String, Object> statusResp = readJsonResponse(statusConn);
        Map<String, Object> statusPayload = (Map<String, Object>) statusResp.get("payload");
        assertEquals("DELETED", statusPayload.get("status"));
        assertNull(statusPayload.get("progress"));
        statusConn.disconnect();

        Thread.sleep(100);
        HttpURLConnection badUuid = openGet(BASE + "/scenario/not-a-uuid/status");
        assertEquals(400, badUuid.getResponseCode());
        badUuid.disconnect();

        Thread.sleep(100);
        HttpURLConnection notFound = openGet(
                BASE + "/scenario/00000000-0000-0000-0000-000000000000/status");
        assertEquals(404, notFound.getResponseCode());
        notFound.disconnect();
    }

    @Test
    @Order(11)
    void testSessionDataGuards() throws Exception {
        Thread.sleep(200);

        HttpURLConnection notFound = sendPostJson(
                BASE + "/scenario/00000000-0000-0000-0000-000000000000/session-data",
                "{\"key\":\"value\"}");
        assertEquals(404, notFound.getResponseCode());
        notFound.disconnect();

        Thread.sleep(100);
        HttpURLConnection badUuid = sendPostJson(
                BASE + "/scenario/not-a-uuid/session-data",
                "{\"key\":\"value\"}");
        assertEquals(400, badUuid.getResponseCode());
        badUuid.disconnect();
    }

    @Test
    @Order(5)
    void testErrorCases() throws Exception {
        List<Map<String, Object>> badUuidSse = sendSseRequest(
                "GET", BASE + "/scenario/not-a-uuid", null, 5000);
        assertFalse(badUuidSse.isEmpty(), "Should receive error for bad UUID");
        assertEquals("error_global", badUuidSse.getFirst().get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> badUuidPayload = (Map<String, Object>) badUuidSse.getFirst().get("payload");
        assertEquals("INVALID_ID", badUuidPayload.get("code"));

        List<Map<String, Object>> notFoundSse = sendSseRequest(
                "GET", BASE + "/scenario/00000000-0000-0000-0000-000000000000", null, 5000);
        assertFalse(notFoundSse.isEmpty(), "Should receive error for not-found scenario");
        assertEquals("error_global", notFoundSse.getFirst().get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> notFoundPayload = (Map<String, Object>) notFoundSse.getFirst().get("payload");
        assertEquals("NOT_FOUND", notFoundPayload.get("code"));

        HttpURLConnection badRest = openGet(BASE + "/scenario/not-a-uuid/maps/emissions");
        assertEquals(400, badRest.getResponseCode());
        badRest.disconnect();

        HttpURLConnection notFoundRest = openGet(
                BASE + "/scenario/00000000-0000-0000-0000-000000000000/maps/emissions");
        assertEquals(404, notFoundRest.getResponseCode());
        notFoundRest.disconnect();
    }

    // --- Server lifecycle helpers ---

    private static void cleanDevArtifacts() throws Exception {
        deleteIfExists(DEV_DATA.resolve("scenarios.mv.db"));
        deleteIfExists(DEV_DATA.resolve("scenarios.trace.db"));
        Path outputDir = DEV_DATA.resolve("output");
        if (Files.isDirectory(outputDir)) {
            try (Stream<Path> walk = Files.walk(outputDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static void deleteIfExists(Path path) throws Exception {
        Files.deleteIfExists(path);
    }

    private static void waitForHealth() throws Exception {
        for (int i = 0; i < HEALTH_TIMEOUT_SEC; i++) {
            if (!serverProcess.isAlive()) {
                fail("Server process died during startup. Check dev-test-output.log");
            }
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        URI.create(BASE + "/health").toURL().openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                if (conn.getResponseCode() == 200) {
                    conn.disconnect();
                    return;
                }
                conn.disconnect();
            } catch (Exception ignored) {
            }
            Thread.sleep(1000);
        }
        fail("Server did not become healthy within " + HEALTH_TIMEOUT_SEC + "s");
    }

    // --- SSE helpers ---

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sendSseRequest(
            String method, String url, String body, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setReadTimeout(timeoutMs);
        conn.setConnectTimeout(5000);
        conn.setRequestProperty("Accept", "text/event-stream");

        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String json = line.substring(5);
                    Map<String, Object> msg = mapper.readValue(json, Map.class);
                    String messageType = (String) msg.get("messageType");
                    if (!"heartbeat".equals(messageType)) {
                        messages.add(msg);
                    }
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            fail("SSE stream timed out after " + timeoutMs + "ms. Received " + messages.size() + " messages so far.");
        } finally {
            conn.disconnect();
        }
        return messages;
    }

    // --- REST helpers ---

    private static HttpURLConnection openGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static HttpURLConnection sendPostJson(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", "application/json");
        if (body != null && !body.isEmpty()) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private static HttpURLConnection sendPutJson(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", "application/json");
        if (body != null && !body.isEmpty()) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private static HttpURLConnection sendDelete(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return mapper.readValue(sb.toString(), Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readErrorJsonResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return mapper.readValue(sb.toString(), Map.class);
        }
    }

    // --- Assertion helpers ---

    private static Map<String, Object> payload(int index) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) liveMessages.get(index).get("payload");
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findPayload(String messageType) {
        return liveMessages.stream()
                .filter(m -> messageType.equals(m.get("messageType")))
                .map(m -> (Map<String, Object>) m.get("payload"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing message type: " + messageType));
    }

    private static void assertType(int index, String expectedType) {
        assertEquals(expectedType, liveMessages.get(index).get("messageType"),
                "Unexpected message type at index " + index);
    }

    private static void assertHasMapSignal(String mapName) {
        boolean hasSignal = liveMessages.stream()
                .anyMatch(m -> {
                    String type = (String) m.get("messageType");
                    return ("success_map_" + mapName).equals(type)
                            || ("error_map_" + mapName).equals(type);
                });
        assertTrue(hasSignal, "Missing map signal for " + mapName);
    }

    private static void assertNonEmptyString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        assertNotNull(val, key + " is null");
        assertInstanceOf(String.class, val, key + " is not a string");
        assertFalse(((String) val).isEmpty(), key + " is empty string");
    }

    @SuppressWarnings("unchecked")
    private static void assertNonEmptyList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        assertNotNull(val, key + " is null");
        assertInstanceOf(List.class, val, key + " is not a list");
        assertFalse(((List<?>) val).isEmpty(), key + " is empty list");
    }

    private static void assertNotNaN(Map<String, Object> map, String key) {
        Object val = map.get(key);
        assertNotNull(val, key + " is null");
        double d = toDouble(val);
        assertFalse(Double.isNaN(d), key + " is NaN");
        assertFalse(Double.isInfinite(d), key + " is infinite");
    }

    private static void assertPositiveNumber(Map<String, Object> map, String key) {
        assertNotNull(map.get(key), key + " is null");
        double val = toDouble(map.get(key));
        assertTrue(val > 0, key + " should be > 0 but was " + val);
    }

    private static void assertNonNegativeNumber(Map<String, Object> map, String key) {
        assertNotNull(map.get(key), key + " is null");
        double val = toDouble(map.get(key));
        assertTrue(val >= 0, key + " should be >= 0 but was " + val);
    }

    private static void assertListSize(Map<String, Object> map, String key, int expectedSize) {
        Object val = map.get(key);
        assertNotNull(val, key + " is null");
        assertInstanceOf(List.class, val, key + " is not a list");
        assertEquals(expectedSize, ((List<?>) val).size(),
                key + " should have " + expectedSize + " elements");
    }

    private static void assertMapSignal(int index, String mapName) {
        String type = (String) liveMessages.get(index).get("messageType");
        assertTrue(type.equals("success_map_" + mapName) || type.equals("error_map_" + mapName),
                "Expected success_map_" + mapName + " or error_map_" + mapName + " but got " + type);
    }

    private static void assertTripLegRecord(Map<String, Object> record) {
        assertNotNull(record.get("legId"), "legId is null");
        assertNotNull(record.get("personId"), "personId is null");
        assertNotNull(record.get("originActivity"), "originActivity is null");
        assertNotNull(record.get("destinationActivity"), "destinationActivity is null");
        assertNotNull(record.get("co2DeltaGrams"), "co2DeltaGrams is null");
        assertNotNull(record.get("timeDeltaMinutes"), "timeDeltaMinutes is null");
        assertNotNull(record.get("impact"), "impact is null");
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return Double.parseDouble(val.toString());
    }
}
