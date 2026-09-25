package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    boolean existsByTelescopeAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            Telescope telescope, ReservationStatus status, Instant endTime, Instant startTime);

    Optional<Reservation> findFirstByTelescopeAndStatusAndEndTimeLessThanEqualOrderByEndTimeDescIdDesc(
            Telescope telescope, ReservationStatus status, Instant startTime);

    Optional<Reservation> findFirstByTelescopeAndStatusAndStartTimeGreaterThanEqualOrderByStartTimeAscIdAsc(
            Telescope telescope, ReservationStatus status, Instant endTime);

    List<Reservation> findByTelescopeAndStatusOrderByStartTimeAscIdAsc(
            Telescope telescope, ReservationStatus status);
}
