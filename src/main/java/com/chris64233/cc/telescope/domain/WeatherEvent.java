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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 天气关闭事件：指定望远镜上的关闭窗口 {@code [windowStart, windowEnd)}。
 *
 * <p>登记时一次性原子阻断窗口内所有<strong>尚未开始</strong>的有效预订（已完成/正在执行的保持不变）、
 * 释放其日程占用，并为每条被阻断预订生成一条可恢复分钟资格（{@link WeatherRecovery}）。
 * 关闭窗口可经范围调整扩大或缩小：扩大时阻断新增重叠预订，缩小时把仍未消耗恢复资格且落出窗口的
 * 预订恢复回日程。实体以 {@code businessKey} 保证登记/调整幂等。
 *
 * <p>本实体只保存审计快照（望远镜以编号引用，与 {@link PreemptionRecord} 的约定一致），
 * 分钟级别的可恢复余额保存在 {@link WeatherRecovery}。
 */
@Entity
@Table(name = "weather_events")
public class WeatherEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String businessKey;

    @Column(nullable = false)
    private String telescopeCode;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    /** 受影响预订快照：每次阻断/恢复都追加一条，历史不删除 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "weather_affected_snapshots", joinColumns = @JoinColumn(name = "weather_event_id"))
    @OrderColumn(name = "position")
    private List<WeatherAffectedSnapshot> affectedReservations = new ArrayList<>();

    /** 操作历史：登记与每次范围调整 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "weather_operations", joinColumns = @JoinColumn(name = "weather_event_id"))
    @OrderColumn(name = "position")
    private List<WeatherOperation> operations = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WeatherEvent() {
    }

    public WeatherEvent(String businessKey, String telescopeCode, Instant windowStart, Instant windowEnd,
                        List<WeatherAffectedSnapshot> blocked, Instant now) {
        this.businessKey = businessKey;
        this.telescopeCode = telescopeCode;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.affectedReservations.addAll(blocked);
        this.operations.add(new WeatherOperation("DECLARED", windowStart, windowEnd, blocked.size(), 0, now));
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 范围调整生效：更新窗口、追加阻断/恢复快照与操作历史。 */
    public void applyAdjustment(Instant newStart, Instant newEnd,
                                List<WeatherAffectedSnapshot> newlyBlocked,
                                List<WeatherAffectedSnapshot> restored, Instant now) {
        this.windowStart = newStart;
        this.windowEnd = newEnd;
        this.affectedReservations.addAll(newlyBlocked);
        this.affectedReservations.addAll(restored);
        this.operations.add(new WeatherOperation("ADJUSTED", newStart, newEnd,
                newlyBlocked.size(), restored.size(), now));
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getTelescopeCode() {
        return telescopeCode;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public List<WeatherAffectedSnapshot> getAffectedReservations() {
        return affectedReservations;
    }

    public List<WeatherOperation> getOperations() {
        return operations;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
