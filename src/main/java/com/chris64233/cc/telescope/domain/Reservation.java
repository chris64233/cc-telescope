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

    /** 普通预订的配额账户；机会预订为 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id")
    private Proposal proposal;

    /** 机会预订的配额账户；普通预订为 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id")
    private OpportunityProposal opportunity;

    /** 机会预订优先级；普通预订为 null，任何机会提案都可抢占普通预订 */
    private Integer priority;

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

    /** 抢占此预订的机会提案；进入待重排状态时写入 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preempted_by_opportunity_id")
    private OpportunityProposal preemptedBy;

    /** 抢占确认业务号（便于从预订侧反查抢占记录） */
    private String preemptedByBusinessKey;

    /** 重排成功后新预订的 ID */
    private Long rescheduledToId;

    private Instant rescheduledAt;

    protected Reservation() {
    }

    public Reservation(String idempotencyKey, Proposal proposal, Telescope telescope, String instrument,
                       Instant startTime, Instant endTime, long durationMinutes) {
        this(idempotencyKey, proposal, null, null, telescope, instrument,
                startTime, endTime, durationMinutes);
    }

    public Reservation(String idempotencyKey, Proposal proposal, OpportunityProposal opportunity,
                       Integer priority, Telescope telescope, String instrument,
                       Instant startTime, Instant endTime, long durationMinutes) {
        if ((proposal == null) == (opportunity == null)) {
            throw new IllegalArgumentException("预订必须且只能关联一个配额账户（普通提案或机会提案）");
        }
        this.idempotencyKey = idempotencyKey;
        this.proposal = proposal;
        this.opportunity = opportunity;
        this.priority = priority;
        this.telescope = telescope;
        this.instrument = instrument;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.status = ReservationStatus.ACTIVE;
        this.createdAt = Instant.now();
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

    public OpportunityProposal getOpportunity() {
        return opportunity;
    }

    public Integer getPriority() {
        return priority;
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

    public OpportunityProposal getPreemptedBy() {
        return preemptedBy;
    }

    public String getPreemptedByBusinessKey() {
        return preemptedByBusinessKey;
    }

    public Long getRescheduledToId() {
        return rescheduledToId;
    }

    public Instant getRescheduledAt() {
        return rescheduledAt;
    }

    public boolean isOpportunityReservation() {
        return opportunity != null;
    }

    public String getOwnerCode() {
        return proposal != null ? proposal.getCode() : opportunity.getCode();
    }

    /** 普通取消：仅 ACTIVE 预订可取消，取消时由调用方归还配额。 */
    public void cancel(Instant cancelledAt) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("只有有效预订可以取消，当前状态: " + status);
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    /** 放弃待重排任务：不涉及配额归还（配额已在抢占时归还）。 */
    public void abandonPending(Instant cancelledAt) {
        if (status != ReservationStatus.PENDING_RESCHEDULE) {
            throw new IllegalStateException("只有待重排预订可以放弃，当前状态: " + status);
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    /** 被机会提案抢占：配额已由调用方归还，进入待重排状态。 */
    public void markPreempted(OpportunityProposal preemptedBy, String businessKey, Instant now) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("只有有效预订可以被抢占，当前状态: " + status);
        }
        this.status = ReservationStatus.PENDING_RESCHEDULE;
        this.preemptedBy = preemptedBy;
        this.preemptedByBusinessKey = businessKey;
        this.cancelledAt = now;
    }

    /**
     * 被更高优先级机会提案抢占的机会预订：机会预订不参与重排，直接取消，
     * 但保留抢占来源信息。配额已由调用方归还。
     */
    public void markPreemptedAndCancelled(OpportunityProposal preemptedBy, String businessKey, Instant now) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("只有有效预订可以被抢占，当前状态: " + status);
        }
        this.status = ReservationStatus.CANCELLED;
        this.preemptedBy = preemptedBy;
        this.preemptedByBusinessKey = businessKey;
        this.cancelledAt = now;
    }

    /** 重排成功：原预订转为 RESCHEDULED，指向新预订。 */
    public void markRescheduled(Long newReservationId, Instant now) {
        if (status != ReservationStatus.PENDING_RESCHEDULE) {
            throw new IllegalStateException("只有待重排预订可以完成重排，当前状态: " + status);
        }
        this.status = ReservationStatus.RESCHEDULED;
        this.rescheduledToId = newReservationId;
        this.rescheduledAt = now;
    }

    public boolean matches(String proposalCode, String telescopeCode, String instrument,
                           Instant startTime, Instant endTime) {
        return getOwnerCode().equals(proposalCode)
                && telescope.getCode().equals(telescopeCode)
                && this.instrument.equals(instrument)
                && this.startTime.equals(startTime)
                && this.endTime.equals(endTime);
    }
}
