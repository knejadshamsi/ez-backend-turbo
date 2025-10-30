package org.mobility.start.sim;

import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StuckAgentHandler implements PersonStuckEventHandler, EventHandler {
    private final JdbcTemplate jdbcTemplate;
    private final List<Map<String, Object>> stuckAgents = new ArrayList<>();
    private String currentRequestId;

    @Autowired
    public StuckAgentHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setRequestId(String requestId) {
        this.currentRequestId = requestId;
        this.stuckAgents.clear();
    }

    @Override
    public void handleEvent(PersonStuckEvent event) {
        Map<String, Object> stuckAgent = new HashMap<>();
        stuckAgent.put("agentId", event.getPersonId().toString());
        stuckAgent.put("linkId", event.getLinkId() != null ? event.getLinkId().toString() : null);
        stuckAgent.put("time", event.getTime());
        stuckAgents.add(stuckAgent);

        if (currentRequestId != null) {
            jdbcTemplate.update(
                "UPDATE agents SET stuck = true WHERE request_id = ? AND agent_id = ?",
                currentRequestId,
                event.getPersonId().toString()
            );
        }
    }

    @Override
    public void reset(int iteration) {
        stuckAgents.clear();
    }
}
