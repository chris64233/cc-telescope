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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条被天气关闭中断的观测记录（受影响提案的恢复凭证）。
 *
 * <p>关闭发生时：原预订被原子标记为 {@link ReservationStatus#WEATHER_CANCELLED}、释放占用，
 * 同时生成本记录，<strong>保留原观测的归属、优先级与可恢复分钟数</strong>。
 * 受影响提案随后可凭此记录，以原优先级和剩余可恢复分钟数重新申请合适时段（恢复排期）。
 *
 * <p>{@code recoverableMinutes} 是该凭证尚可用于恢复排期的分钟余额：每次恢复成功按新观测时长扣减，
 * 全部用完进入 {@link WeatherAffectedStatus#RECOVERED}；放弃则清零并进入
 * {@link WeatherAffectedStatus#ABANDONED}；天气范围缩小不再覆盖且原时段可恢复时进入
 * {@link WeatherAffectedStatus#RESTORED}。
 */
@Entity
@Table(name = "weather_affected_records")
public class WeatherAffectedRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "weather_event_id", nullable = false)
    private WeatherEvent event;

    /**
     * 被中断的原预订。非唯一：缩窗还原（RESTORED）后若窗口再次扩大，同一预订可产生新的受影响记录。
     */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "original_reservation_id", nullable = false)
    private Reservation originalReservation;

    /** NORMAL / OPPORTUNITY（关闭时快照） */
    @Column(nullable = false)
    private String ownerType;

    @Column(nullable = false)
    private String ownerCode;

    /** 原观测优先级：普通观测为 null，机会观测保留其整数优先级，恢复时沿用。 */
    private Integer priority;

    @Column(nullable = false)
    private String instrument;

    @Column(nullable = false)
    private Instant originalStartTime;

    @Column(nullable = false)
    private Instant originalEndTime;

    @Column(nullable = false)
    private long originalDurationMinutes;

    /** 剩余可恢复分钟数（恢复排期的分钟资格余额）。 */
    @Column(nullable = false)
    private long recoverableMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WeatherAffectedStatus status;

    /** 历次恢复排期建立的新预订（支持部分恢复，可能有多条）。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "weather_recovery_bookings", joinColumns = @JoinColumn(name = "affected_id"))
    @OrderColumn(name = "position")
    private List<WeatherRecoveryBooking> recoveryBookings = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    private Instant recoveredAt;

    private Instant abandonedAt;

    private Instant restoredAt;

    protected WeatherAffectedRecord() {
    }

    public WeatherAffectedRecord(WeatherEvent event, Reservation originalReservation, String ownerType,
                                 String ownerCode, Integer priority, String instrument,
                                 Instant originalStartTime, Instant originalEndTime,
                                 long originalDurationMinutes, Instant createdAt) {
        this.event = event;
        this.originalReservation = originalReservation;
        this.ownerType = ownerType;
        this.ownerCode = ownerCode;
        this.priority = priority;
        this.instrument = instrument;
        this.originalStartTime = originalStartTime;
        this.originalEndTime = originalEndTime;
        this.originalDurationMinutes = originalDurationMinutes;
        this.recoverableMinutes = originalDurationMinutes;
        this.status = WeatherAffectedStatus.AFFECTED;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public WeatherEvent getEvent() {
        return event;
    }

    public Reservation getOriginalReservation() {
        return originalReservation;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public String getOwnerCode() {
        return ownerCode;
    }

    public Integer getPriority() {
        return priority;
    }

    public String getInstrument() {
        return instrument;
    }

    public Instant getOriginalStartTime() {
        return originalStartTime;
    }

    public Instant getOriginalEndTime() {
        return originalEndTime;
    }

    public long getOriginalDurationMinutes() {
        return originalDurationMinutes;
    }

    public long getRecoverableMinutes() {
        return recoverableMinutes;
    }

    public WeatherAffectedStatus getStatus() {
        return status;
    }

    public List<WeatherRecoveryBooking> getRecoveryBookings() {
        return recoveryBookings;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }

    public Instant getAbandonedAt() {
        return abandonedAt;
    }

    public Instant getRestoredAt() {
        return restoredAt;
    }

    public boolean isOpportunityOwner() {
        return "OPPORTUNITY".equals(ownerType);
    }

    /** 恢复排期成功：扣减可恢复分钟数并登记新预订；余额清零则完结。调用方须已持有行锁。 */
    public void applyRecovery(Long newReservationId, long usedMinutes, Instant now) {
        if (status != WeatherAffectedStatus.AFFECTED) {
            throw new IllegalStateException("只有待恢复记录可以恢复排期，当前状态: " + status);
        }
        if (usedMinutes <= 0 || usedMinutes > recoverableMinutes) {
            throw new IllegalStateException("恢复分钟数非法: used=" + usedMinutes
                    + ", recoverable=" + recoverableMinutes);
        }
        this.recoverableMinutes -= usedMinutes;
        this.recoveryBookings.add(new WeatherRecoveryBooking(newReservationId, usedMinutes, now));
        if (recoverableMinutes == 0) {
            this.status = WeatherAffectedStatus.RECOVERED;
            this.recoveredAt = now;
        }
    }

    /** 放弃待恢复任务：可恢复分钟数清零，不涉及配额（配额在关闭时已释放）。 */
    public void abandon(Instant now) {
        if (status != WeatherAffectedStatus.AFFECTED) {
            throw new IllegalStateException("只有待恢复记录可以放弃，当前状态: " + status);
        }
        this.status = WeatherAffectedStatus.ABANDONED;
        this.recoverableMinutes = 0;
        this.abandonedAt = now;
    }

    /** 天气范围缩小不再覆盖、原时段已恢复：凭证失效。 */
    public void markRestored(Instant now) {
        if (status != WeatherAffectedStatus.AFFECTED) {
            throw new IllegalStateException("只有待恢复记录可以还原，当前状态: " + status);
        }
        this.status = WeatherAffectedStatus.RESTORED;
        this.recoverableMinutes = 0;
        this.restoredAt = now;
    }
}
