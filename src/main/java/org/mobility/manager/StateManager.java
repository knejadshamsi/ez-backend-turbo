package org.mobility.manager;

import org.matsim.core.mobsim.qsim.QSim;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StateManager {
    private final Map<String, SimulationState> simulations = new ConcurrentHashMap<>();

    public void registerSimulation(String requestId, QSim qsim, Future<?> future) {
        if (requestId == null || qsim == null) {
            return;
        }

        try {
            UUID.fromString(requestId);
            simulations.put(requestId, new SimulationState(qsim, future));
        } catch (IllegalArgumentException e) {
            return;
        }
    }

    public void requestCancellation(String requestId) {
        if (requestId == null) {
            return;
        }

        try {
            UUID.fromString(requestId);
            SimulationState state = simulations.get(requestId);
            if (state != null) {
                state.cancellationRequested.set(true);
                if (state.future != null && !state.future.isDone()) {
                    state.future.cancel(true);
                }
            }
        } catch (IllegalArgumentException e) {
            return;
        }
    }

    public boolean isCancellationRequested(String requestId) {
        if (requestId == null) {
            return false;
        }

        try {
            UUID.fromString(requestId);
            SimulationState state = simulations.get(requestId);
            return state != null && state.cancellationRequested.get();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public QSim getQSim(String requestId) {
        if (requestId == null) {
            return null;
        }

        try {
            UUID.fromString(requestId);
            SimulationState state = simulations.get(requestId);
            return state != null ? state.qsim : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void removeState(String requestId) {
        if (requestId == null) {
            return;
        }

        try {
            UUID.fromString(requestId);
            simulations.remove(requestId);
        } catch (IllegalArgumentException e) {
            return;
        }
    }

    private static class SimulationState {
        final QSim qsim;
        final Future<?> future;
        final AtomicBoolean cancellationRequested;

        SimulationState(QSim qsim, Future<?> future) {
            this.qsim = qsim;
            this.future = future;
            this.cancellationRequested = new AtomicBoolean(false);
        }
    }
}
