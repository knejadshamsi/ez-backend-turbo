package ez.backend.turbo.database;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DraftRepository {

    private final JdbcTemplate jdbcTemplate;

    public DraftRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID draftId, String inputData, String sessionData, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO drafts (draft_id, input_data, session_data, created_at) VALUES (?, ?, ?, ?)",
                draftId, inputData, sessionData, Timestamp.from(now)
        );
    }

    public Optional<Map<String, Object>> findById(UUID draftId) {
        var results = jdbcTemplate.query(
                "SELECT draft_id, input_data, session_data, created_at FROM drafts WHERE draft_id = ?",
                (rs, rowNum) -> mapRow(rs),
                draftId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int update(UUID draftId, String inputData, String sessionData) {
        return jdbcTemplate.update(
                "UPDATE drafts SET input_data = ?, session_data = ? WHERE draft_id = ?",
                inputData, sessionData, draftId
        );
    }

    public int deleteById(UUID draftId) {
        return jdbcTemplate.update("DELETE FROM drafts WHERE draft_id = ?", draftId);
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("draftId", UUID.fromString(rs.getString("draft_id")));
        row.put("inputData", rs.getString("input_data"));
        row.put("sessionData", rs.getString("session_data"));
        row.put("createdAt", rs.getTimestamp("created_at").toInstant());
        return row;
    }
}
