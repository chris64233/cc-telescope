package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    List<Reservation> findByTelescopeAndStatusOrderByStartTimeAscIdAsc(
            Telescope telescope, ReservationStatus status);

    /** 日程只读查询：立即加载提案/机会提案/望远镜关联，保证事务外可安全访问。 */
    @Query("""
            select r from Reservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            left join fetch r.fundedByWeatherRecovery
            where r.telescope = :telescope and r.status = :status
            order by r.startTime asc, r.id asc
            """)
    List<Reservation> findScheduleForRead(@Param("telescope") Telescope telescope,
                                          @Param("status") ReservationStatus status);

    /** 单预订只读查询：立即加载全部关联。 */
    @Query("""
            select r from Reservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            left join fetch r.preemptedBy
            left join fetch r.weatherEvent
            where r.id = :id
            """)
    Optional<Reservation> findDetailedById(@Param("id") Long id);

    /** 预订关联的普通提案 ID（机会预订时为 null；预订不存在时也为 null）。 */
    @Query("select r.proposal.id from Reservation r where r.id = :id")
    Long findProposalIdById(@Param("id") Long id);

    /** 预订关联的机会提案 ID（普通预订时为 null；预订不存在时也为 null）。 */
    @Query("select r.opportunity.id from Reservation r where r.id = :id")
    Long findOpportunityIdById(@Param("id") Long id);

    /** 仅取预订所在望远镜编号（不加载实体进一级缓存）。 */
    @Query("select r.telescope.code from Reservation r where r.id = :id")
    String findTelescopeCodeById(@Param("id") Long id);

    /**
     * 抢占加锁前的标量快照（不把预订实体加载进一级缓存，保证随后的 FOR UPDATE 首次加载到最新状态）。
     * 每行：[id, 是否机会账户, 账户ID, startTime, endTime]。调用方须已持有望远镜行锁。
     */
    @Query("""
            select r.id,
                   case when r.opportunity is not null then true else false end,
                   coalesce(r.opportunity.id, r.proposal.id),
                   r.startTime, r.endTime,
                   r.fundedByWeatherRecovery.id
            from Reservation r
            where r.telescope = :telescope and r.status = :status
            order by r.startTime asc, r.id asc
            """)
    List<Object[]> findOverlapScalars(@Param("telescope") Telescope telescope,
                                      @Param("status") ReservationStatus status);

    /**
     * 悲观锁住某望远镜上的全部有效预订。修改日程的事务（预订/抢占/重排/取消）
     * 必须先持有望远镜行锁，再调用本方法，使望远镜上的日程变更全局串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.telescope = :telescope and r.status = :status "
            + "order by r.startTime asc, r.id asc")
    List<Reservation> findActiveByTelescopeForUpdate(@Param("telescope") Telescope telescope,
                                                     @Param("status") ReservationStatus status);

    /** 查找某机会提案抢占后仍处于待重排状态的预订（关联实体立即加载，便于事务外序列化）。 */
    @Query("""
            select r from Reservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            join fetch r.preemptedBy
            where r.status = :status and r.preemptedBy.code = :opportunityCode
            order by r.id asc
            """)
    List<Reservation> findPendingForReschedule(@Param("status") ReservationStatus status,
                                               @Param("opportunityCode") String opportunityCode);

    /**
     * 按 ID 升序悲观锁定一批预订行。天气关闭登记/范围调整与恢复排期在持有望远镜行锁后，
     * 用本方法一次性按统一顺序锁定全部相关预订（有效预订 + 被阻断原预订），消除跨流程死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id in :ids order by r.id asc")
    List<Reservation> findByIdInForUpdate(@Param("ids") Collection<Long> ids);

    /** 加锁前的标量定位：预订关联的天气事件 ID（未被天气阻断时为 null）。 */
    @Query("select r.weatherEvent.id from Reservation r where r.id = :id")
    Long findWeatherEventIdById(@Param("id") Long id);

    /** 加锁前的标量定位：出资的天气恢复资格 ID（普通配额出资时为 null）。 */
    @Query("select r.fundedByWeatherRecovery.id from Reservation r where r.id = :id")
    Long findFundedRecoveryIdById(@Param("id") Long id);

    /** 按 ID 批量只读抓取详情（立即加载全部关联），供恢复资格详情关联历次新预订。 */
    @Query("""
            select r from Reservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            left join fetch r.preemptedBy
            left join fetch r.weatherEvent
            where r.id in :ids
            order by r.id asc
            """)
    List<Reservation> findDetailedByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 清空预订对天气恢复资格的出资外键（仅测试清理使用，解除预订↔恢复资格的循环引用，
     * 使恢复资格表可先于预订表删除）。
     */
    @Modifying
    @Transactional
    @Query("update Reservation r set r.fundedByWeatherRecovery = null")
    int clearFundedWeatherRecoveryReferences();
}
