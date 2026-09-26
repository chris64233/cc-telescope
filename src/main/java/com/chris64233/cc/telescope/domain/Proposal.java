package com.chris64233.cc.telescope.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "proposals")
public class Proposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "proposal_instruments", joinColumns = @JoinColumn(name = "proposal_id"))
    @Column(name = "instrument", nullable = false)
    private Set<String> allowedInstruments = new LinkedHashSet<>();

    @Column(nullable = false)
    private long totalQuotaMinutes;

    @Column(nullable = false)
    private long remainingQuotaMinutes;

    /** 是否为目标机会（ToO）提案；普通提案为 false。 */
    @Column(nullable = false)
    private boolean targetOpportunity;

    /** 目标机会提案优先级，数值越大优先级越高；普通提案为 null。 */
    private Integer priority;

    /**
     * 有效期截止时间（含响应时限语义）：
     * ToO 提案必须在此之前完成抢占确认；被抢占预订只能在此之前重排。
     * 普通提案可为 null（不限制重排窗口）。
     */
    private Instant validUntil;

    protected Proposal() {
    }

    public Proposal(String code, Set<String> allowedInstruments, long totalQuotaMinutes) {
        this(code, allowedInstruments, totalQuotaMinutes, false, null, null);
    }

    public Proposal(String code, Set<String> allowedInstruments, long totalQuotaMinutes,
                    boolean targetOpportunity, Integer priority, Instant validUntil) {
        this.code = code;
        this.allowedInstruments = new LinkedHashSet<>(allowedInstruments);
        this.totalQuotaMinutes = totalQuotaMinutes;
        this.remainingQuotaMinutes = totalQuotaMinutes;
        this.targetOpportunity = targetOpportunity;
        this.priority = priority;
        this.validUntil = validUntil;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
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

    public boolean isTargetOpportunity() {
        return targetOpportunity;
    }

    public Integer getPriority() {
        return priority;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public boolean allows(String instrument) {
        return allowedInstruments.contains(instrument);
    }

    public void deduct(long minutes) {
        this.remainingQuotaMinutes -= minutes;
    }

    public void refund(long minutes) {
        this.remainingQuotaMinutes += minutes;
    }
}
