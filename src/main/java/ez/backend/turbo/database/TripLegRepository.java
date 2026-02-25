package ez.backend.turbo.database;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TripLegRepository {

    private final JdbcTemplate jdbcTemplate;

    public TripLegRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void batchInsert(UUID requestId, List<TripLegRecord> legs) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.batchUpdate(
                "INSERT INTO trip_legs (request_id, leg_id, person_id, origin_activity_type, " +
                        "destination_activity_type, co2_delta_grams, time_delta_minutes, impact, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                legs, legs.size(),
                (ps, leg) -> {
                    ps.setObject(1, requestId);
                    ps.setString(2, leg.legId());
                    ps.setString(3, leg.personId());
                    ps.setString(4, leg.originActivityType());
                    ps.setString(5, leg.destinationActivityType());
                    ps.setBigDecimal(6, leg.co2DeltaGrams());
                    ps.setBigDecimal(7, leg.timeDeltaMinutes());
                    ps.setString(8, leg.impact());
                    ps.setTimestamp(9, now);
                });
    }

    public List<Map<String, Object>> findByRequestId(UUID requestId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return jdbcTemplate.query(
                "SELECT leg_id, person_id, origin_activity_type, destination_activity_type, " +
                        "co2_delta_grams, time_delta_minutes, impact FROM trip_legs " +
                        "WHERE request_id = ? ORDER BY id LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("legId", rs.getString("leg_id"));
                    row.put("personId", rs.getString("person_id"));
                    row.put("originActivity", rs.getString("origin_activity_type"));
                    row.put("destinationActivity", rs.getString("destination_activity_type"));
                    row.put("co2DeltaGrams", rs.getBigDecimal("co2_delta_grams"));
                    row.put("timeDeltaMinutes", rs.getBigDecimal("time_delta_minutes"));
                    row.put("impact", rs.getString("impact"));
                    return row;
                },
                requestId, pageSize, offset);
    }

    public int deleteByRequestId(UUID requestId) {
        return jdbcTemplate.update("DELETE FROM trip_legs WHERE request_id = ?", requestId);
    }

    public int countByRequestId(UUID requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_legs WHERE request_id = ?",
                Integer.class, requestId);
        return count != null ? count : 0;
    }

    public record TripLegRecord(
            String legId,
            String personId,
            String originActivityType,
            String destinationActivityType,
            java.math.BigDecimal co2DeltaGrams,
            java.math.BigDecimal timeDeltaMinutes,
            String impact) {}
}
