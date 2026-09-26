package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id", nullable = false)
    private Proposal proposal;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "telescope_id", nullable = false)
    private Telescope telescope;

    @Column(nullable = false)
    private String instrument;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private long durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant cancelledAt;

    /** 抢占该预订的目标机会抢占单。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preempted_by_id")
    private Preemption preemptedBy;

    /** 被抢占的时间点（进入 PENDING_REARRANGE 的时间）。 */
    private Instant preemptedAt;

    /** 若该预订是重排产生的新预订，指向被重排的原预订。 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rearranged_from_id")
    private Reservation rearrangedFrom;

    /** 若该预订已完成重排，指向重排后的新预订。 */
    @OneToOne(mappedBy = "rearrangedFrom", fetch = FetchType.LAZY)
    private Reservation rearrangedTo;

    protected Reservation() {
    }

    public Reservation(String idempotencyKey, Proposal proposal, Telescope telescope, String instrument,
                       Instant startTime, Instant endTime, long durationMinutes) {
        this(idempotencyKey, proposal, telescope, instrument, startTime, endTime, durationMinutes, null);
    }

    public Reservation(String idempotencyKey, Proposal proposal, Telescope telescope, String instrument,
                       Instant startTime, Instant endTime, long durationMinutes, Reservation rearrangedFrom) {
        this.idempotencyKey = idempotencyKey;
        this.proposal = proposal;
        this.telescope = telescope;
        this.instrument = instrument;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.status = ReservationStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.rearrangedFrom = rearrangedFrom;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Proposal getProposal() {
        return proposal;
    }

    public Telescope getTelescope() {
        return telescope;
    }

    public String getInstrument() {
        return instrument;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getDurationMinutes() {
        return durationMinutes;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Preemption getPreemptedBy() {
        return preemptedBy;
    }

    public Instant getPreemptedAt() {
        return preemptedAt;
    }

    public Reservation getRearrangedFrom() {
        return rearrangedFrom;
    }

    public Reservation getRearrangedTo() {
        return rearrangedTo;
    }

    public void cancel(Instant cancelledAt) {
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    /** 被目标机会抢占：退出日程，等待在提案剩余有效期内重排。抢占单在保存后通过 {@link #assignPreemption} 关联。 */
    public void markPendingRearrange(Instant preemptedAt) {
        this.status = ReservationStatus.PENDING_REARRANGE;
        this.preemptedAt = preemptedAt;
    }

    /** 关联导致该预订进入待重排状态的抢占单（抢占单持久化后调用）。 */
    public void assignPreemption(Preemption preemption) {
        this.preemptedBy = preemption;
    }

    /** 重排成功：原预订进入终态。 */
    public void markPreemptedResolved(Reservation rearrangedTo) {
        this.status = ReservationStatus.PREEMPTED;
        this.rearrangedTo = rearrangedTo;
    }

    public boolean matches(String proposalCode, String telescopeCode, String instrument,
                           Instant startTime, Instant endTime) {
        return proposal.getCode().equals(proposalCode)
                && telescope.getCode().equals(telescopeCode)
                && this.instrument.equals(instrument)
                && this.startTime.equals(startTime)
                && this.endTime.equals(endTime);
    }
}
