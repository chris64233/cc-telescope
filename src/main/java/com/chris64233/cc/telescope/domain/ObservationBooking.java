package com.chris64233.cc.telescope.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "observation_booking",
        indexes = {
                @Index(name = "idx_booking_telescope_start", columnList = "telescope_id,start_at"),
                @Index(name = "idx_booking_idempotency", columnList = "idempotency_key")
        })
public class ObservationBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private Proposal proposal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "telescope_id", nullable = false)
    private Telescope telescope;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    /**
     * 起始时间（含），区间采用左闭右开语义。
     */
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    /**
     * 结束时间（不含）。
     */
    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BookingStatus status = BookingStatus.BOOKED;

    @Column(name = "idempotency_key", length = 128, unique = true)
    private String idempotencyKey;

    protected ObservationBooking() {
    }

    public ObservationBooking(Proposal proposal, Telescope telescope, Instrument instrument,
                              Instant startAt, Instant endAt, int durationMinutes,
                              String idempotencyKey) {
        this.proposal = proposal;
        this.telescope = telescope;
        this.instrument = instrument;
        this.startAt = startAt;
        this.endAt = endAt;
        this.durationMinutes = durationMinutes;
        this.idempotencyKey = idempotencyKey;
        this.status = BookingStatus.BOOKED;
    }

    public Long getId() {
        return id;
    }

    public Proposal getProposal() {
        return proposal;
    }

    public Telescope getTelescope() {
        return telescope;
    }

    public Instrument getInstrument() {
        return instrument;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean isCancelled() {
        return status == BookingStatus.CANCELLED;
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
    }
}
