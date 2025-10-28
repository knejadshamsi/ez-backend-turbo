package org.ez.mobility.api;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;

@Entity
public class SimulationStatus {
    public enum SimulationState {
        RECEIVED,
        PREPARING,
        RUNNING,
        PROCESSING,
        STORING,
        COMPLETED,
        CANCELLED,
        FAILED_PREPARING,
        FAILED_RUNNING,
        FAILED_PROCESSING,
        FAILED_STORING
    }

    @Id
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimulationState state;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public SimulationState getState() {
        return state;
    }

    public void setState(SimulationState state) {
        this.state = state;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return String.format("SimulationStatus[requestId=%s, state=%s, errorMessage=%s, createdAt=%s]",
                requestId, state, errorMessage, createdAt);
    }
}
