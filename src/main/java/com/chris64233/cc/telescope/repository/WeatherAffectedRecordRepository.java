package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.WeatherAffectedRecord;
import com.chris64233.cc.telescope.domain.WeatherAffectedStatus;
import com.chris64233.cc.telescope.domain.WeatherEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WeatherAffectedRecordRepository extends JpaRepository<WeatherAffectedRecord, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WeatherAffectedRecord a where a.id = :id")
    Optional<WeatherAffectedRecord> findByIdForUpdate(@Param("id") Long id);

    /** 只读详情：立即加载天气事件、原预订及其望远镜/归属，保证事务外可安全序列化。 */
    @Query("""
            select a from WeatherAffectedRecord a
            join fetch a.event e
            join fetch e.telescope
            join fetch a.originalReservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            where a.id = :id
            """)
    Optional<WeatherAffectedRecord> findDetailedById(@Param("id") Long id);

    /** 某天气事件下的全部受影响记录（关联立即加载，便于事务外序列化）。 */
    @Query("""
            select a from WeatherAffectedRecord a
            join fetch a.event e
            join fetch e.telescope
            join fetch a.originalReservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            where e.businessKey = :businessKey
            order by a.id asc
            """)
    List<WeatherAffectedRecord> findDetailedByEventKey(@Param("businessKey") String businessKey);

    /**
     * 受影响记录原预订的账户定位 [是否机会账户, 账户ID]（标量，不加载预订实体进一级缓存）。
     * 用 List 承载多列元组（Hibernate 对单结果 Object[] 会按单列处理）。
     */
    @Query("""
            select case when r.opportunity is not null then true else false end,
                   coalesce(r.opportunity.id, r.proposal.id)
            from WeatherAffectedRecord a join a.originalReservation r
            where a.id = :id
            """)
    List<Object[]> findAccountScalarById(@Param("id") Long id);

    /**
     * 范围调整缩窗时的标量快照（不加锁、不把实体加载进一级缓存），用于先按规范锁定配额账户。
     * 每行：[受影响记录ID, 是否机会账户, 账户ID, 原开始, 原结束]。
     * 调用方须已持有望远镜行锁与事件行锁。
     */
    @Query("""
            select a.id,
                   case when r.opportunity is not null then true else false end,
                   coalesce(r.opportunity.id, r.proposal.id),
                   a.originalStartTime, a.originalEndTime
            from WeatherAffectedRecord a join a.originalReservation r
            where a.event = :event and a.status = :status
            order by a.id asc
            """)
    List<Object[]> findScalarsByEventAndStatus(@Param("event") WeatherEvent event,
                                               @Param("status") WeatherAffectedStatus status);

    /**
     * 加锁加载某天气事件下处于指定状态的受影响记录（范围调整缩窗使用）。
     * 调用方须已按规范先锁定望远镜、事件与相关配额账户，再调用本方法。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from WeatherAffectedRecord a
            join fetch a.originalReservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            where a.event = :event and a.status = :status
            order by a.id asc
            """)
    List<WeatherAffectedRecord> findByEventAndStatusForUpdate(@Param("event") WeatherEvent event,
                                                              @Param("status") WeatherAffectedStatus status);

    /** 通过某次恢复排期建立的新预订 ID 反查受影响记录（幂等重放使用）。 */
    @Query("""
            select a from WeatherAffectedRecord a join a.recoveryBookings b
            where b.newReservationId = :newReservationId
            """)
    Optional<WeatherAffectedRecord> findByRecoveryBookingId(@Param("newReservationId") Long newReservationId);

    /**
     * 放弃时定位加锁锚点 [望远镜编号, 天气事件ID]（标量，不把实体加载进一级缓存）。
     * 用 List 承载多列元组（Hibernate 对单结果 Object[] 会按单列处理）。
     */
    @Query("""
            select t.code, e.id
            from WeatherAffectedRecord a join a.event e join e.telescope t
            where a.id = :id
            """)
    List<Object[]> findLockRefScalarById(@Param("id") Long id);
}
