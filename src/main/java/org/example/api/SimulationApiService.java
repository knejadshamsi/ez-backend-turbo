package org.example.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SimulationApiService {
    private static final Logger logger = LoggerFactory.getLogger(SimulationApiService.class);
    private static final String API_BASE_URL = "http://localhost:5000/api";
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public SimulationApiService() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public boolean updateSimulationConfig(
            List<String> zoneLinks,
            LocalTime operatingStart,
            LocalTime operatingEnd,
            Map<String, Double> chargingStructures,
            Map<String, Integer> lengthRestrictions,
            Map<String, String> accessibilityModifications) {
        
        try {
            ObjectNode configNode = objectMapper.createObjectNode();
            configNode.put("zoneLinks", objectMapper.valueToTree(zoneLinks));
            
            ObjectNode operatingHours = objectMapper.createObjectNode();
            operatingHours.put("start", operatingStart.toString());
            operatingHours.put("end", operatingEnd.toString());
            configNode.set("operatingHours", operatingHours);
            
            configNode.put("chargingStructures", objectMapper.valueToTree(chargingStructures));
            configNode.put("lengthRestrictions", objectMapper.valueToTree(lengthRestrictions));
            configNode.put("accessibilityModifications", objectMapper.valueToTree(accessibilityModifications));

            RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(configNode),
                JSON
            );

            Request request = new Request.Builder()
                .url(API_BASE_URL + "/simulation/config")
                .post(body)
                .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Failed to update simulation config. Status: {}", response.code());
                    return false;
                }
                
                logger.info("Successfully updated simulation configuration");
                return true;
            }

        } catch (Exception e) {
            logger.error("Error updating simulation configuration", e);
            return false;
        }
    }

    public SimulationResults getSimulationResults() throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE_URL + "/simulation/results")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get simulation results: " + response.code());
            }

            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, SimulationResults.class);
        }
    }

    public boolean isApiHealthy() {
        Request request = new Request.Builder()
                .url(API_BASE_URL + "/health")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            logger.error("Error checking API health", e);
            return false;
        }
    }
}
