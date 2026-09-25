package com.chris64233.cc.telescope.domain;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "proposal")
public class Proposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 提案唯一编号。
     */
    @Column(name = "proposal_no", nullable = false, unique = true)
    private String proposalNo;

    /**
     * 总观测分钟配额。
     */
    @Column(name = "total_minutes", nullable = false)
    private int totalMinutes;

    /**
     * 已预订（含已开始）分钟数，取消未开始预订时归还。
     */
    @Column(name = "used_minutes", nullable = false)
    private int usedMinutes;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "proposal_instrument",
            joinColumns = @JoinColumn(name = "proposal_id"),
            inverseJoinColumns = @JoinColumn(name = "instrument_id"))
    private Set<Instrument> allowedInstruments = new HashSet<>();

    protected Proposal() {
    }

    public Proposal(String proposalNo, int totalMinutes) {
        this.proposalNo = proposalNo;
        this.totalMinutes = totalMinutes;
        this.usedMinutes = 0;
    }

    public Long getId() {
        return id;
    }

    public String getProposalNo() {
        return proposalNo;
    }

    public int getTotalMinutes() {
        return totalMinutes;
    }

    public int getUsedMinutes() {
        return usedMinutes;
    }

    public int getRemainingMinutes() {
        return totalMinutes - usedMinutes;
    }

    public Set<Instrument> getAllowedInstruments() {
        return allowedInstruments;
    }

    public boolean allows(Instrument instrument) {
        return allowedInstruments.contains(instrument);
    }

    public void addAllowedInstrument(Instrument instrument) {
        allowedInstruments.add(instrument);
    }

    /**
     * 扣减配额，超出剩余配额时抛出异常。
     */
    public void consume(int minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("预订时长必须为正数");
        }
        if (minutes > getRemainingMinutes()) {
            throw new QuotaExceededException(proposalNo, minutes, getRemainingMinutes());
        }
        usedMinutes += minutes;
    }

    /**
     * 归还配额。调用方需保证同一次预订只归还一次。
     */
    public void refund(int minutes) {
        usedMinutes -= minutes;
        if (usedMinutes < 0) {
            usedMinutes = 0;
        }
    }
}
