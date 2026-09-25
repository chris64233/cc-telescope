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

    protected Proposal() {
    }

    public Proposal(String code, Set<String> allowedInstruments, long totalQuotaMinutes) {
        this.code = code;
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

    public void deduct(long minutes) {
        this.remainingQuotaMinutes -= minutes;
    }

    public void refund(long minutes) {
        this.remainingQuotaMinutes += minutes;
    }
}
