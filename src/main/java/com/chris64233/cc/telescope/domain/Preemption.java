package com.chris64233.cc.telescope.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 目标机会抢占单。以业务号幂等，保存确认时的日程版本、受影响预订快照以及
 * 抢占前后的完整日程 JSON。拒绝时同样落库（状态 REJECTED + 冲突原因），
 * 原日程完全不变。
 */
@Entity
@Table(name = "preemptions")
public class Preemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String businessKey;

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
    private PreemptionStatus status;

    @Column(nullable = false)
    private long scheduleVersionBefore;

    @Column(nullable = false)
    private long scheduleVersionAfter;

    /** 抢占前该望远镜有效日程的 JSON 快照。 */
    @Column(length = 32_768)
    private String scheduleBeforeJson;

    /** 抢占后该望远镜有效日程的 JSON 快照。 */
    @Column(length = 32_768)
    private String scheduleAfterJson;

    /** 目标机会提案抢占前后的配额快照。 */
    @Column(nullable = false)
    private long tooQuotaBeforeMinutes;

    @Column(nullable = false)
    private long tooQuotaAfterMinutes;

    /** 冲突原因（CODE:说明），按计算顺序保存；确认时为空列表。 */
    @OneToMany(mappedBy = "preemption", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("id")
    private List<PreemptionReason> reasons = new ArrayList<>();

    /** 受影响预订快照；拒绝时为空列表。 */
    @OneToMany(mappedBy = "preemption", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("id")
    private List<PreemptionItem> items = new ArrayList<>();

    /** 抢占建立的新预订；确认时非空。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_reservation_id")
    private Reservation newReservation;

    @Column(nullable = false)
    private Instant createdAt;

    protected Preemption() {
    }

    public Preemption(String businessKey, Proposal proposal, Telescope telescope, String instrument,
                      Instant startTime, Instant endTime, long durationMinutes, Instant createdAt) {
        this.businessKey = businessKey;
        this.proposal = proposal;
        this.telescope = telescope;
        this.instrument = instrument;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getBusinessKey() {
        return businessKey;
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

    public PreemptionStatus getStatus() {
        return status;
    }

    public long getScheduleVersionBefore() {
        return scheduleVersionBefore;
    }

    public long getScheduleVersionAfter() {
        return scheduleVersionAfter;
    }

    public String getScheduleBeforeJson() {
        return scheduleBeforeJson;
    }

    public String getScheduleAfterJson() {
        return scheduleAfterJson;
    }

    public long getTooQuotaBeforeMinutes() {
        return tooQuotaBeforeMinutes;
    }

    public long getTooQuotaAfterMinutes() {
        return tooQuotaAfterMinutes;
    }

    public List<PreemptionReason> getReasons() {
        return reasons;
    }

    public List<PreemptionItem> getItems() {
        return items;
    }

    public Reservation getNewReservation() {
        return newReservation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean sameRequest(Proposal otherProposal, Telescope otherTelescope, String otherInstrument,
                               Instant otherStartTime, Instant otherEndTime) {
        return proposal.getId().equals(otherProposal.getId())
                && telescope.getId().equals(otherTelescope.getId())
                && instrument.equals(otherInstrument)
                && startTime.equals(otherStartTime)
                && endTime.equals(otherEndTime);
    }

    public void reject(long scheduleVersionBefore, String scheduleBeforeJson,
                       List<String> reasons, Instant rejectedAt) {
        this.status = PreemptionStatus.REJECTED;
        this.scheduleVersionBefore = scheduleVersionBefore;
        this.scheduleVersionAfter = scheduleVersionBefore;
        this.scheduleBeforeJson = scheduleBeforeJson;
        this.scheduleAfterJson = scheduleBeforeJson;
        this.tooQuotaBeforeMinutes = proposal.getRemainingQuotaMinutes();
        this.tooQuotaAfterMinutes = proposal.getRemainingQuotaMinutes();
        this.createdAt = rejectedAt;
        for (String reason : reasons) {
            this.reasons.add(new PreemptionReason(this, reason));
        }
    }

    public void confirm(long scheduleVersionBefore, long scheduleVersionAfter,
                        String scheduleBeforeJson, String scheduleAfterJson,
                        long tooQuotaBefore, long tooQuotaAfter,
                        List<PreemptionItem> items, Reservation newReservation, Instant confirmedAt) {
        this.status = PreemptionStatus.CONFIRMED;
        this.scheduleVersionBefore = scheduleVersionBefore;
        this.scheduleVersionAfter = scheduleVersionAfter;
        this.scheduleBeforeJson = scheduleBeforeJson;
        this.scheduleAfterJson = scheduleAfterJson;
        this.tooQuotaBeforeMinutes = tooQuotaBefore;
        this.tooQuotaAfterMinutes = tooQuotaAfter;
        this.newReservation = newReservation;
        this.createdAt = confirmedAt;
        for (PreemptionItem item : items) {
            item.setPreemption(this);
            this.items.add(item);
        }
    }
}
