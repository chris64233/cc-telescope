package com.chris64233.cc.telescope.repo;

import java.util.List;
import java.util.Optional;

import com.chris64233.cc.telescope.domain.BookingStatus;
import com.chris64233.cc.telescope.domain.ObservationBooking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationBookingRepository extends JpaRepository<ObservationBooking, Long> {

    Optional<ObservationBooking> findByIdempotencyKey(String idempotencyKey);

    List<ObservationBooking> findByTelescopeIdAndStatusOrderByStartAtAscIdAsc(
            Long telescopeId, BookingStatus status);

    List<ObservationBooking> findByProposalIdOrderByStartAtAscIdAsc(Long proposalId);
}
