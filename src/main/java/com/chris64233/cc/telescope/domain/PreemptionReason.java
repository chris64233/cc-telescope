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

/** 抢占被拒绝时的一条冲突原因（CODE:说明）。 */
@Entity
@Table(name = "preemption_reasons")
public class PreemptionReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "preemption_id", nullable = false)
    private Preemption preemption;

    @Column(nullable = false, length = 512)
    private String reason;

    protected PreemptionReason() {
    }

    public PreemptionReason(Preemption preemption, String reason) {
        this.preemption = preemption;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getReason() {
        return reason;
    }
}
