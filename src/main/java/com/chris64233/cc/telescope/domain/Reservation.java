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

    protected Reservation() {
    }

    public Reservation(String idempotencyKey, Proposal proposal, Telescope telescope, String instrument,
                       Instant startTime, Instant endTime, long durationMinutes) {
        this.idempotencyKey = idempotencyKey;
        this.proposal = proposal;
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

    public void cancel(Instant cancelledAt) {
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
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
