package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 抢占单中单个被影响普通预订的确认时快照：记录其原时段、仪器、
 * 时长（随后归还的配额分钟数）以及被抢占后的新状态。
 */
@Entity
@Table(name = "preemption_items")
public class PreemptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "preemption_id", nullable = false)
    private Preemption preemption;

    /** 被影响预订的 ID（保留 ID，即使预订实体后续变化）。 */
    @Column(nullable = false)
    private Long reservationId;

    @Column(nullable = false)
    private String proposalCode;

    @Column(nullable = false)
    private String instrument;

    @Column(nullable = false)
    private java.time.Instant startTime;

    @Column(nullable = false)
    private java.time.Instant endTime;

    @Column(nullable = false)
    private long durationMinutes;

    protected PreemptionItem() {
    }

    public PreemptionItem(Reservation reservation) {
        this.reservationId = reservation.getId();
        this.proposalCode = reservation.getProposal().getCode();
        this.instrument = reservation.getInstrument();
        this.startTime = reservation.getStartTime();
        this.endTime = reservation.getEndTime();
        this.durationMinutes = reservation.getDurationMinutes();
    }

    void setPreemption(Preemption preemption) {
        this.preemption = preemption;
    }

    public Long getId() {
        return id;
    }

    public Preemption getPreemption() {
        return preemption;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public String getProposalCode() {
        return proposalCode;
    }

    public String getInstrument() {
        return instrument;
    }

    public java.time.Instant getStartTime() {
        return startTime;
    }

    public java.time.Instant getEndTime() {
        return endTime;
    }

    public long getDurationMinutes() {
        return durationMinutes;
    }
}
