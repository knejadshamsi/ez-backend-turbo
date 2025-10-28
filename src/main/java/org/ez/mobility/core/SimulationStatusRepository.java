package org.ez.mobility.core;

import org.ez.mobility.api.SimulationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SimulationStatusRepository extends JpaRepository<SimulationStatus, String> {
    boolean existsByRequestId(String requestId);

    @Query("SELECT s.requestId FROM SimulationStatus s WHERE s.createdAt < :cutoffTime")
    List<String> findOldRequestIds(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("DELETE FROM SimulationStatus s WHERE s.createdAt < :cutoffTime")
    int deleteByCreatedBefore(@Param("cutoffTime") LocalDateTime cutoffTime);
}
