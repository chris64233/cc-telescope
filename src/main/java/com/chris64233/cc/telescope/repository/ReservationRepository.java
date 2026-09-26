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

    /**
     * 仅取路由信息（望远镜编号、提案 ID），不把实体加载进持久化上下文，
     * 供"先锁望远镜/提案、再锁预订"的加锁顺序使用，避免读到未加锁前的过期实体状态。
     */
    @Query("select r.telescope.code as telescopeCode, r.proposal.id as proposalId "
            + "from Reservation r where r.id = :id")
    Optional<ReservationRouting> findRoutingById(@Param("id") Long id);

    interface ReservationRouting {
        String getTelescopeCode();

        Long getProposalId();
    }

    boolean existsByTelescopeAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            Telescope telescope, ReservationStatus status, Instant endTime, Instant startTime);

    List<Reservation> findByTelescopeAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            Telescope telescope, ReservationStatus status, Instant endTime, Instant startTime);

    Optional<Reservation> findFirstByTelescopeAndStatusAndEndTimeLessThanEqualOrderByEndTimeDescIdDesc(
            Telescope telescope, ReservationStatus status, Instant startTime);

    Optional<Reservation> findFirstByTelescopeAndStatusAndStartTimeGreaterThanEqualOrderByStartTimeAscIdAsc(
            Telescope telescope, ReservationStatus status, Instant endTime);

    List<Reservation> findByTelescopeAndStatusOrderByStartTimeAscIdAsc(
            Telescope telescope, ReservationStatus status);

    List<Reservation> findByStatusAndProposal_CodeOrderByPreemptedAtAscIdAsc(
            ReservationStatus status, String proposalCode);
}
