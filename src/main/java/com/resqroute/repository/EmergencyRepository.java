package com.resqroute.repository;

import com.resqroute.model.Emergency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmergencyRepository extends JpaRepository<Emergency, Long> {

    List<Emergency> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * Atomically claims one emergency for dispatch.
     *
     * The WHERE clause makes "only if still PENDING" a single
     * database statement: if two transactions race to dispatch the
     * same emergency, exactly one UPDATE matches a row — the loser
     * gets 0 rows and must fail. This is the same pattern as the
     * ambulance reservation (AmbulanceRepository.updateStatusIfCurrent),
     * so an emergency can never be dispatched twice.
     *
     * clearAutomatically keeps the persistence context in sync with
     * the direct UPDATE. Must be called inside a transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Emergency e " +
           "SET e.status = :newStatus " +
           "WHERE e.id = :id AND e.status = :expectedStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("expectedStatus") String expectedStatus);
}
