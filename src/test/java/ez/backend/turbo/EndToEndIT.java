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

        int idx = 0;

        // pa_request_accepted
        Map<String, Object> accepted = payload(idx++);
        requestId = (String) accepted.get("requestId");
        assertNotNull(requestId);
        assertDoesNotThrow(() -> UUID.fromString(requestId));

        // pa_simulation_start
        assertType(idx++, "pa_simulation_start");

        // data_text_overview
        Map<String, Object> overview = payload(idx++);
        assertType(idx - 1, "data_text_overview");
        assertPositiveNumber(overview, "personCount");
        assertPositiveNumber(overview, "legCount");
        assertPositiveNumber(overview, "totalKmTraveled");
        assertPositiveNumber(overview, "networkNodes");
        assertPositiveNumber(overview, "networkLinks");
        assertPositiveNumber(overview, "simulationAreaKm2");

        // data_text_paragraph1_emissions
        Map<String, Object> emP1 = payload(idx++);
        assertType(idx - 1, "data_text_paragraph1_emissions");
        assertNonNegativeNumber(emP1, "co2Baseline");
        assertNonNegativeNumber(emP1, "co2PostPolicy");
        double modeShift = toDouble(emP1.get("modeShiftPercentage"));
        assertTrue(modeShift >= 0 && modeShift <= 100,
                "modeShiftPercentage out of range: " + modeShift);

        // data_text_paragraph2_emissions
        Map<String, Object> emP2 = payload(idx++);
        assertType(idx - 1, "data_text_paragraph2_emissions");
        assertPositiveNumber(emP2, "zoneArea");

        // data_chart_bar_emissions
        Map<String, Object> barChart = payload(idx++);
        assertType(idx - 1, "data_chart_bar_emissions");
        assertListSize(barChart, "baselineData", 4);
        assertListSize(barChart, "postPolicyData", 4);

        // data_chart_pie_emissions
        Map<String, Object> pieChart = payload(idx++);
        assertType(idx - 1, "data_chart_pie_emissions");
        assertListSize(pieChart, "vehicleBaselineData", 5);
        assertListSize(pieChart, "vehiclePostPolicyData", 5);

        // success_map_emissions or error_map_emissions
        assertMapSignal(idx++, "emissions");

        // data_text_paragraph1_people_response
        assertType(idx, "data_text_paragraph1_people_response");
        assertNotNull(payload(idx++), "paragraph1 people response is null");

        // data_text_paragraph2_people_response
        assertType(idx, "data_text_paragraph2_people_response");
        assertNotNull(payload(idx++), "paragraph2 people response is null");

        // data_chart_breakdown_people_response
        assertType(idx, "data_chart_breakdown_people_response");
        assertNotNull(payload(idx++), "breakdown chart is null");

        // data_chart_time_impact_people_response
        assertType(idx, "data_chart_time_impact_people_response");
        assertNotNull(payload(idx++), "time impact chart is null");

        // success_map_people_response or error_map_people_response
        assertMapSignal(idx++, "people_response");

        // data_table_trip_legs
        Map<String, Object> tripLegs = payload(idx++);
        assertType(idx - 1, "data_table_trip_legs");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) tripLegs.get("records");
        assertNotNull(records, "trip legs records is null");
        assertFalse(records.isEmpty(), "trip legs records is empty");
        int totalRecords = ((Number) tripLegs.get("totalRecords")).intValue();
        assertTrue(totalRecords > 0, "totalRecords should be > 0");
        assertEquals(50, ((Number) tripLegs.get("pageSize")).intValue());
        assertTripLegRecord(records.getFirst());

        // success_map_trip_legs or error_map_trip_legs
        assertMapSignal(idx++, "trip_legs");

        // success_process
        assertType(idx, "success_process");

        assertEquals(16, liveMessages.size(),
                "Expected 16 messages, got " + liveMessages.size() + ": " + types);
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
        assertEquals(requestId, statusPayload.get("requestId"));

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

        List<Map<String, Object>> liveDataMessages = liveMessages.stream()
                .filter(m -> {
                    String type = (String) m.get("messageType");
                    return !"pa_request_accepted".equals(type) && !"pa_simulation_start".equals(type);
                })
                .toList();

        assertEquals(liveDataMessages.size(), outputMessages.size(),
                "Output message count mismatch: expected " + liveDataMessages.size()
                        + " got " + outputMessages.size());

        for (int i = 0; i < liveDataMessages.size(); i++) {
            Map<String, Object> live = liveDataMessages.get(i);
            Map<String, Object> replay = outputMessages.get(i);

            assertEquals(live.get("messageType"), replay.get("messageType"),
                    "Message type mismatch at index " + i);

            var liveTree = mapper.valueToTree(live.get("payload"));
            var replayTree = mapper.valueToTree(replay.get("payload"));
            assertEquals(liveTree, replayTree,
                    "Payload mismatch for " + live.get("messageType") + " at index " + i);
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
        assertFalse(records.isEmpty(), "page 1 records should not be empty");
        int totalRecords = ((Number) payload.get("totalRecords")).intValue();
        assertTrue(totalRecords > 0);
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
                "chart_pie_emissions",
                "text_paragraph1_people_response",
                "text_paragraph2_people_response",
                "chart_breakdown_people_response",
                "chart_time_impact_people_response"
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
        assertEquals(3, refetchMessages.size(), "Deleted refetch should send 3 preamble messages only");
        assertEquals("scenario_status", refetchMessages.get(0).get("messageType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> deletedStatus = (Map<String, Object>) refetchMessages.get(0).get("payload");
        assertEquals("DELETED", deletedStatus.get("status"));
        assertEquals("scenario_input", refetchMessages.get(1).get("messageType"));
        assertEquals("scenario_session", refetchMessages.get(2).get("messageType"));

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

    private static void assertType(int index, String expectedType) {
        assertEquals(expectedType, liveMessages.get(index).get("messageType"),
                "Unexpected message type at index " + index);
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
