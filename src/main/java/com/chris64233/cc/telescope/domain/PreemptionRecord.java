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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次抢占申请/确认的持久化记录。
 *
 * <p>以 {@code businessKey}（抢占业务号）保证幂等：同一业务号重放返回原记录；
 * 业务号相同但申请内容不同返回冲突。无论确认（CONFIRMED）还是拒绝（REJECTED）都会落库；
 * 拒绝时保存冲突原因与受影响预订快照，且原日程、配额完全不变。
 */
@Entity
@Table(name = "preemption_records")
public class PreemptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String businessKey;

    @Column(nullable = false)
    private String opportunityCode;

    @Column(nullable = false)
    private String telescopeCode;

    @Column(nullable = false)
    private String instrument;

    @Column(nullable = false)
    private Instant requestedStart;

    @Column(nullable = false)
    private Instant requestedEnd;

    @Column(nullable = false)
    private long durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PreemptionStatus status;

    /** 抢占前日程版本 */
    @Column(nullable = false)
    private long scheduleVersionBefore;

    /** 抢占后日程版本；被拒绝时与抢占前相同 */
    @Column(nullable = false)
    private long scheduleVersionAfter;

    /** 前邻预订结束到期望区间开始需要的仪器切换时长（分钟），0 表示同仪器/无前邻 */
    @Column(nullable = false)
    private long switchBeforeMinutes;

    /** 期望区间结束到后邻预订开始需要的仪器切换时长（分钟），0 表示同仪器/无后邻 */
    @Column(nullable = false)
    private long switchAfterMinutes;

    /** 抢占确认后建立的机会预订 ID；被拒绝时为 null */
    private Long opportunityReservationId;

    /** 与期望区间重叠的全部预订快照（含不可抢占的阻塞预订），按开始时间排序 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "preemption_snapshots", joinColumns = @JoinColumn(name = "preemption_id"))
    @OrderColumn(name = "position")
    private List<PreemptionSnapshot> affectedReservations = new ArrayList<>();

    /** 抢占涉及的配额变化（归还/消费） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "preemption_quota_changes", joinColumns = @JoinColumn(name = "preemption_id"))
    @OrderColumn(name = "position")
    private List<QuotaChange> quotaChanges = new ArrayList<>();

    /** 拒绝原因；确认时为空 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "preemption_conflict_reasons", joinColumns = @JoinColumn(name = "preemption_id"))
    @Column(name = "reason", nullable = false)
    @OrderColumn(name = "position")
    private List<String> conflictReasons = new ArrayList<>();

    /** 抢占前望远镜完整有效日程快照 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "preemption_schedule_before", joinColumns = @JoinColumn(name = "preemption_id"))
    @OrderColumn(name = "position")
    private List<ScheduleEntry> scheduleBefore = new ArrayList<>();

    /** 抢占后望远镜完整有效日程快照 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "preemption_schedule_after", joinColumns = @JoinColumn(name = "preemption_id"))
    @OrderColumn(name = "position")
    private List<ScheduleEntry> scheduleAfter = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    protected PreemptionRecord() {
    }

    public PreemptionRecord(String businessKey, String opportunityCode, String telescopeCode, String instrument,
                            Instant requestedStart, Instant requestedEnd, long durationMinutes,
                            PreemptionStatus status, long scheduleVersionBefore, long scheduleVersionAfter,
                            long switchBeforeMinutes, long switchAfterMinutes, Long opportunityReservationId,
                            List<PreemptionSnapshot> affectedReservations, List<QuotaChange> quotaChanges,
                            List<String> conflictReasons, List<ScheduleEntry> scheduleBefore,
                            List<ScheduleEntry> scheduleAfter, Instant createdAt) {
        this.businessKey = businessKey;
        this.opportunityCode = opportunityCode;
        this.telescopeCode = telescopeCode;
        this.instrument = instrument;
        this.requestedStart = requestedStart;
        this.requestedEnd = requestedEnd;
        this.durationMinutes = durationMinutes;
        this.status = status;
        this.scheduleVersionBefore = scheduleVersionBefore;
        this.scheduleVersionAfter = scheduleVersionAfter;
        this.switchBeforeMinutes = switchBeforeMinutes;
        this.switchAfterMinutes = switchAfterMinutes;
        this.opportunityReservationId = opportunityReservationId;
        this.affectedReservations = new ArrayList<>(affectedReservations);
        this.quotaChanges = new ArrayList<>(quotaChanges);
        this.conflictReasons = new ArrayList<>(conflictReasons);
        this.scheduleBefore = new ArrayList<>(scheduleBefore);
        this.scheduleAfter = new ArrayList<>(scheduleAfter);
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getOpportunityCode() {
        return opportunityCode;
    }

    public String getTelescopeCode() {
        return telescopeCode;
    }

    public String getInstrument() {
        return instrument;
    }

    public Instant getRequestedStart() {
        return requestedStart;
    }

    public Instant getRequestedEnd() {
        return requestedEnd;
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

    public long getSwitchBeforeMinutes() {
        return switchBeforeMinutes;
    }

    public long getSwitchAfterMinutes() {
        return switchAfterMinutes;
    }

    public Long getOpportunityReservationId() {
        return opportunityReservationId;
    }

    public List<PreemptionSnapshot> getAffectedReservations() {
        return affectedReservations;
    }

    public List<QuotaChange> getQuotaChanges() {
        return quotaChanges;
    }

    public List<String> getConflictReasons() {
        return conflictReasons;
    }

    public List<ScheduleEntry> getScheduleBefore() {
        return scheduleBefore;
    }

    public List<ScheduleEntry> getScheduleAfter() {
        return scheduleAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
