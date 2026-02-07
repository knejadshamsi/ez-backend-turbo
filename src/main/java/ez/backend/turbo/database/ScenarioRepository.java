package ez.backend.turbo.database;

import ez.backend.turbo.L;
import ez.backend.turbo.services.ScenarioStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// JDBC operations for the scenarios table
@Repository
public class ScenarioRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScenarioRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID requestId, ScenarioStatus status, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO scenarios (request_id, status, created_at, updated_at) VALUES (?::uuid, ?, ?, ?)",
                requestId.toString(), status.name(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    public int updateStatus(UUID requestId, ScenarioStatus status, Instant now) {
        return jdbcTemplate.update(
                "UPDATE scenarios SET status = ?, updated_at = ? WHERE request_id = ?::uuid",
                status.name(), Timestamp.from(now), requestId.toString()
        );
    }

    public Optional<Map<String, Object>> findById(UUID requestId) {
        var results = jdbcTemplate.query(
                "SELECT request_id, status, input_data, session_data, created_at, updated_at FROM scenarios WHERE request_id = ?::uuid",
                (rs, rowNum) -> mapRow(rs),
                requestId.toString()
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<ScenarioStatus> findStatusById(UUID requestId) {
        var results = jdbcTemplate.query(
                "SELECT status FROM scenarios WHERE request_id = ?::uuid",
                (rs, rowNum) -> ScenarioStatus.valueOf(rs.getString("status")),
                requestId.toString()
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int updateInputData(UUID requestId, String inputDataJson, Instant now) {
        if (inputDataJson == null) {
            throw new IllegalArgumentException(L.msg("scenario.input.null"));
        }
        return jdbcTemplate.update(
                "UPDATE scenarios SET input_data = ?::jsonb, updated_at = ? WHERE request_id = ?::uuid",
                inputDataJson, Timestamp.from(now), requestId.toString()
        );
    }

    public int updateSessionData(UUID requestId, String sessionDataJson, Instant now) {
        if (sessionDataJson == null) {
            throw new IllegalArgumentException(L.msg("scenario.session.null"));
        }
        return jdbcTemplate.update(
                "UPDATE scenarios SET session_data = ?::jsonb, updated_at = ? WHERE request_id = ?::uuid",
                sessionDataJson, Timestamp.from(now), requestId.toString()
        );
    }

    public List<UUID> findIncompleteScenarios() {
        return jdbcTemplate.query(
                "SELECT request_id FROM scenarios WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
                (rs, rowNum) -> UUID.fromString(rs.getString("request_id"))
        );
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("requestId", UUID.fromString(rs.getString("request_id")));
        row.put("status", ScenarioStatus.valueOf(rs.getString("status")));
        row.put("inputData", rs.getString("input_data"));
        row.put("sessionData", rs.getString("session_data"));
        row.put("createdAt", rs.getTimestamp("created_at").toInstant());
        row.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
        return row;
    }
}
