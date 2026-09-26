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
@Table(name = "telescopes")
public class Telescope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long switchMinutes;

    /** 日程版本：每次该望远镜日程发生结构性变化（新建/取消/抢占/重排）后递增。 */
    @Column(nullable = false)
    private long scheduleVersion;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "telescope_instruments", joinColumns = @JoinColumn(name = "telescope_id"))
    @Column(name = "instrument", nullable = false)
    private Set<String> instruments = new LinkedHashSet<>();

    protected Telescope() {
    }

    public Telescope(String code, String name, long switchMinutes, Set<String> instruments) {
        this.code = code;
        this.name = name;
        this.switchMinutes = switchMinutes;
        this.instruments = new LinkedHashSet<>(instruments);
        this.scheduleVersion = 0;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public long getSwitchMinutes() {
        return switchMinutes;
    }

    public long getScheduleVersion() {
        return scheduleVersion;
    }

    public void incrementScheduleVersion() {
        this.scheduleVersion++;
    }

    public Set<String> getInstruments() {
        return instruments;
    }

    public boolean supports(String instrument) {
        return instruments.contains(instrument);
    }
}
