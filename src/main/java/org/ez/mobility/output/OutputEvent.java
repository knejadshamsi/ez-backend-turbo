package org.ez.mobility.output;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.Convert;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;

@Entity
@Table(name = "simulation_events")
public class OutputEvent {
    private static final Logger logger = LoggerFactory.getLogger(OutputEvent.class);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "event_time", nullable = false)
    private double eventTime;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "attributes", columnDefinition = "TEXT")
    @Convert(converter = MapConverter.class)
    private Map<String, String> attributes = new HashMap<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public double getEventTime() {
        return eventTime;
    }

    public void setEventTime(double eventTime) {
        this.eventTime = eventTime;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    @Converter
    public static class MapConverter implements AttributeConverter<Map<String, String>, String> {
        private static final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            try {
                return attribute != null ? objectMapper.writeValueAsString(attribute) : null;
            } catch (JsonProcessingException e) {
                logger.error("Error converting map to JSON", e);
                return "{}";
            }
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            try {
                return dbData != null ? 
                    objectMapper.readValue(dbData, new TypeReference<Map<String, String>>() {}) : 
                    new HashMap<>();
            } catch (JsonProcessingException e) {
                logger.error("Error converting JSON to map", e);
                return new HashMap<>();
            }
        }
    }
}
