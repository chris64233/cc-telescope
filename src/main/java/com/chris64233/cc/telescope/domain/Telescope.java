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
@Table(name = "telescope")
public class Telescope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /**
     * 每次切换仪器需要的固定准备时长（分钟）。
     */
    @Column(name = "switch_over_minutes", nullable = false)
    private int switchOverMinutes;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "telescope_instrument",
            joinColumns = @JoinColumn(name = "telescope_id"),
            inverseJoinColumns = @JoinColumn(name = "instrument_id"))
    private Set<Instrument> supportedInstruments = new HashSet<>();

    protected Telescope() {
    }

    public Telescope(String code, String name, int switchOverMinutes) {
        this.code = code;
        this.name = name;
        this.switchOverMinutes = switchOverMinutes;
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

    public int getSwitchOverMinutes() {
        return switchOverMinutes;
    }

    public Set<Instrument> getSupportedInstruments() {
        return supportedInstruments;
    }

    public boolean supports(Instrument instrument) {
        return supportedInstruments.contains(instrument);
    }

    public void addInstrument(Instrument instrument) {
        supportedInstruments.add(instrument);
    }
}
