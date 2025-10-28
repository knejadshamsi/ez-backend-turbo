package org.ez.mobility.output;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface OutputEventRepository extends JpaRepository<OutputEvent, Long> {
    
    List<OutputEvent> findByRequestId(String requestId);
    
    @Query("SELECT e FROM OutputEvent e WHERE e.requestId = :requestId AND e.eventType = :eventType")
    List<OutputEvent> findByRequestIdAndEventType(
        @Param("requestId") String requestId, 
        @Param("eventType") String eventType
    );
    
    @Query("SELECT COUNT(e) FROM OutputEvent e WHERE e.requestId = :requestId")
    long countByRequestId(@Param("requestId") String requestId);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM OutputEvent e WHERE e.requestId = :requestId")
    void deleteByRequestId(@Param("requestId") String requestId);

    @Modifying
    @Transactional
    @Query("DELETE FROM OutputEvent e WHERE e.requestId IN :requestIds")
    int deleteByRequestIdIn(@Param("requestIds") Collection<String> requestIds);

    @Query(value = "SELECT e.* FROM simulation_events e WHERE e.request_id = :requestId " +
           "ORDER BY e.event_time LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<OutputEvent> findByRequestIdPaginated(
        @Param("requestId") String requestId,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    @Query("SELECT MIN(e.eventTime) FROM OutputEvent e WHERE e.requestId = :requestId")
    Double findFirstEventTime(@Param("requestId") String requestId);

    @Query("SELECT MAX(e.eventTime) FROM OutputEvent e WHERE e.requestId = :requestId")
    Double findLastEventTime(@Param("requestId") String requestId);

    @Query("SELECT DISTINCT e.eventType FROM OutputEvent e WHERE e.requestId = :requestId")
    List<String> findEventTypes(@Param("requestId") String requestId);

    @Query(value = "INSERT INTO simulation_events (request_id, event_time, event_type, attributes) " +
           "VALUES (:#{#event.requestId}, :#{#event.eventTime}, :#{#event.eventType}, " +
           "CAST(:#{#event.attributes} AS jsonb))", nativeQuery = true)
    @Modifying
    @Transactional
    void insertEvent(@Param("event") OutputEvent event);

    default void saveInBatch(List<OutputEvent> events, int batchSize) {
        int total = events.size();
        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            saveAll(events.subList(i, end));
            flush();
        }
    }
}
