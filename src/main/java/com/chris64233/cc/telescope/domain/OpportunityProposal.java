package com.chris64233.cc.telescope.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 目标机会（Target of Opportunity）提案。
 *
 * <p>除普通提案的允许仪器与分钟配额外，还携带：
 * <ul>
 *     <li>{@code priority}：数值越大优先级越高，仅当机会提案优先级严格高于被覆盖机会预订的优先级时才能抢占；</li>
 *     <li>{@code responseDeadline}：响应时限，抢占与被抢占预订的重排都必须落在此时限之内。</li>
 * </ul>
 */
@Entity
@Table(name = "opportunity_proposals")
public class OpportunityProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Instant responseDeadline;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "opportunity_instruments", joinColumns = @JoinColumn(name = "opportunity_id"))
    @Column(name = "instrument", nullable = false)
    private Set<String> allowedInstruments = new LinkedHashSet<>();

    @Column(nullable = false)
    private long totalQuotaMinutes;

    @Column(nullable = false)
    private long remainingQuotaMinutes;

    protected OpportunityProposal() {
    }

    public OpportunityProposal(String code, int priority, Instant responseDeadline,
                               Set<String> allowedInstruments, long totalQuotaMinutes) {
        this.code = code;
        this.priority = priority;
        this.responseDeadline = responseDeadline;
        this.allowedInstruments = new LinkedHashSet<>(allowedInstruments);
        this.totalQuotaMinutes = totalQuotaMinutes;
        this.remainingQuotaMinutes = totalQuotaMinutes;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Integer getPriority() {
        return priority;
    }

    public Instant getResponseDeadline() {
        return responseDeadline;
    }

    public Set<String> getAllowedInstruments() {
        return allowedInstruments;
    }

    public long getTotalQuotaMinutes() {
        return totalQuotaMinutes;
    }

    public long getRemainingQuotaMinutes() {
        return remainingQuotaMinutes;
    }

    public boolean allows(String instrument) {
        return allowedInstruments.contains(instrument);
    }

    public boolean deadlineReached(Instant now) {
        return !now.isBefore(responseDeadline);
    }

    public void deduct(long minutes) {
        this.remainingQuotaMinutes -= minutes;
    }

    public void refund(long minutes) {
        this.remainingQuotaMinutes += minutes;
    }
}
