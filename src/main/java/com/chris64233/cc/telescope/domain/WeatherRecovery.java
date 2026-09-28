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
 * 一条被天气阻断预订的<strong>可恢复分钟资格账户</strong>。
 *
 * <p>预订被关闭窗口阻断时，其占用的分钟数不退还到提案的可消费配额，而是冻结为本资格：
 * 受影响提案日后使用<strong>原预订优先级</strong>重新申请时段时，仅消耗本资格的剩余分钟
 * （提案剩余可消费配额不变），从而在任何并发下都不会重复释放或重复消费分钟数。
 *
 * <p>资格支持多次部分恢复：每次建立一条新预订即消耗对应分钟；耗尽后原预订进入 {@code RECOVERED} 终态。
 * 放弃被阻断预订（取消）或关闭窗口缩小使预订落出范围时，资格分别作废/撤销。
 */
@Entity
@Table(name = "weather_recoveries")
public class WeatherRecovery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "weather_event_id", nullable = false)
    private WeatherEvent weatherEvent;

    /** 被阻断的原预订（同一预订在多轮扩大/缩小后可能产生多代资格） */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation originalReservation;

    /** 阻断时冻结的可恢复总分钟数（等于原预订时长） */
    @Column(nullable = false)
    private long blockedMinutes;

    /** 已通过恢复排期消耗的分钟数 */
    @Column(nullable = false)
    private long recoveredMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WeatherRecoveryStatus status;

    /** 历次恢复建立的新预订 ID（支持部分恢复后继续申请） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "weather_recovery_bookings", joinColumns = @JoinColumn(name = "weather_recovery_id"))
    @Column(name = "reservation_id", nullable = false)
    @OrderColumn(name = "position")
    private List<Long> recoveredReservationIds = new ArrayList<>();

    @Column(nullable = false)
    private Instant blockedAt;

    private Instant lastRecoveredAt;

    private Instant closedAt;

    protected WeatherRecovery() {
    }

    public WeatherRecovery(WeatherEvent weatherEvent, Reservation originalReservation, Instant now) {
        this.weatherEvent = weatherEvent;
        this.originalReservation = originalReservation;
        this.blockedMinutes = originalReservation.getDurationMinutes();
        this.recoveredMinutes = 0;
        this.status = WeatherRecoveryStatus.BLOCKED;
        this.blockedAt = now;
    }

    /**
     * 恢复排期消耗分钟并登记新预订。仅 BLOCKED/PARTIALLY_RECOVERED 可消耗，
     * 且消耗分钟不得超过剩余可恢复分钟。全额耗尽后进入 RECOVERED 终态。
     */
    public void consume(long minutes, Long newReservationId, Instant now) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("恢复分钟数必须为正数");
        }
        if (status != WeatherRecoveryStatus.BLOCKED && status != WeatherRecoveryStatus.PARTIALLY_RECOVERED) {
            throw new IllegalStateException("当前恢复资格状态不允许恢复排期: " + status);
        }
        if (minutes > getRemainingRecoverableMinutes()) {
            throw new IllegalStateException("可恢复分钟不足：需要 " + minutes + " 分钟，剩余 "
                    + getRemainingRecoverableMinutes() + " 分钟");
        }
        this.recoveredMinutes += minutes;
        this.recoveredReservationIds.add(newReservationId);
        this.lastRecoveredAt = now;
        this.status = this.recoveredMinutes >= this.blockedMinutes
                ? WeatherRecoveryStatus.RECOVERED
                : WeatherRecoveryStatus.PARTIALLY_RECOVERED;
        if (this.status == WeatherRecoveryStatus.RECOVERED) {
            this.closedAt = now;
        }
    }

    /** 放弃恢复（取消被阻断预订）：资格作废；剩余可恢复分钟由调用方一次性退还配额账户。 */
    public void forfeit(Instant now) {
        if (status != WeatherRecoveryStatus.BLOCKED && status != WeatherRecoveryStatus.PARTIALLY_RECOVERED) {
            throw new IllegalStateException("仅可恢复的阻断预订可以放弃，当前状态: " + status);
        }
        this.status = WeatherRecoveryStatus.FORFEITED;
        this.closedAt = now;
    }

    /** 关闭窗口缩小使预订落出范围：仅 BLOCKED 可撤销资格，预订恢复为有效。 */
    public void restore(Instant now) {
        if (status != WeatherRecoveryStatus.BLOCKED) {
            throw new IllegalStateException("仅未消耗恢复资格的被阻断预订可以恢复回日程，当前状态: " + status);
        }
        this.status = WeatherRecoveryStatus.RESTORED;
        this.closedAt = now;
    }

    /**
     * 一笔已恢复建立的新预订被取消/抢占时，把其分钟退回本资格（与 {@link #consume} 对称），
     * 使这些分钟可再次用于恢复排期，而不是重复退还提案配额。
     *
     * @return true 表示资格从 RECOVERED 终态重新打开为 BLOCKED，调用方应把原预订恢复为天气阻断状态
     */
    public boolean releaseBack(long minutes, Long reservationIdToRemove, Instant now) {
        if (minutes <= 0 || minutes > recoveredMinutes) {
            throw new IllegalArgumentException("退回恢复分钟数非法: " + minutes);
        }
        boolean wasFullyRecovered = status == WeatherRecoveryStatus.RECOVERED;
        this.recoveredMinutes -= minutes;
        this.recoveredReservationIds.remove(reservationIdToRemove);
        this.status = this.recoveredMinutes == 0
                ? WeatherRecoveryStatus.BLOCKED
                : WeatherRecoveryStatus.PARTIALLY_RECOVERED;
        if (this.status == WeatherRecoveryStatus.BLOCKED) {
            this.lastRecoveredAt = null;
        }
        this.closedAt = null;
        return wasFullyRecovered;
    }

    public Long getId() {
        return id;
    }

    public WeatherEvent getWeatherEvent() {
        return weatherEvent;
    }

    public Reservation getOriginalReservation() {
        return originalReservation;
    }

    public long getBlockedMinutes() {
        return blockedMinutes;
    }

    public long getRecoveredMinutes() {
        return recoveredMinutes;
    }

    public long getRemainingRecoverableMinutes() {
        return blockedMinutes - recoveredMinutes;
    }

    public WeatherRecoveryStatus getStatus() {
        return status;
    }

    public List<Long> getRecoveredReservationIds() {
        return recoveredReservationIds;
    }

    public Instant getBlockedAt() {
        return blockedAt;
    }

    public Instant getLastRecoveredAt() {
        return lastRecoveredAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
