package com.resqroute.repository;

import com.resqroute.model.Ambulance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AmbulanceRepository extends JpaRepository<Ambulance, Long> {

    List<Ambulance> findByStatusIgnoreCase(String status);

    /**
     * Atomically reserves one ambulance for a dispatch.
     *
     * The WHERE clause makes the check-and-set a SINGLE database
     * statement, so two emergencies racing for the same ambulance
     * can never both win: the first UPDATE changes the status, and
     * the second UPDATE then matches zero rows and returns 0.
     *
     * clearAutomatically keeps the persistence context in sync with
     * the direct UPDATE, so later reads see the real status.
     *
     * Must be called inside a transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ambulance a " +
           "SET a.status = :newStatus " +
           "WHERE a.id = :id AND a.status = :expectedStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("expectedStatus") String expectedStatus);
}
