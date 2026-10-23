package com.resqroute.repository;

import com.resqroute.model.Dispatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DispatchRepository extends JpaRepository<Dispatch, Long> {

    /** Newest ACTIVE dispatch (what the dashboard follows). */
    Optional<Dispatch> findFirstByStatusOrderByCreatedAtDesc(String status);

    /** Newest dispatch of any status (used when nothing is active). */
    Optional<Dispatch> findFirstByOrderByCreatedAtDesc();

    List<Dispatch> findByStatusOrderByCreatedAtDesc(String status);

    /** The dispatch a victim's private tracking token points to. */
    Optional<Dispatch> findByTrackingToken(String trackingToken);

    Optional<Dispatch> findFirstByAmbulanceIdAndStatusOrderByIdDesc(
            Long ambulanceId, String status);

    long countByAmbulanceId(Long ambulanceId);
}
