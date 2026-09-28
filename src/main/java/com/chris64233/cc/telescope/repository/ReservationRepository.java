package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
                   r.startTime, r.endTime
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
     * 天气流程加锁前的标量快照（语义同 {@link #findOverlapScalars}，但状态可指定，
     * 用于范围缩小时读取 WEATHER_CANCELLED 预订的账户定位）。
     * 每行：[id, 是否机会账户, 账户ID, startTime, endTime]。调用方须已持有望远镜行锁。
     */
    @Query("""
            select r.id,
                   case when r.opportunity is not null then true else false end,
                   coalesce(r.opportunity.id, r.proposal.id),
                   r.startTime, r.endTime
            from Reservation r
            where r.telescope = :telescope and r.status = :status
            order by r.startTime asc, r.id asc
            """)
    List<Object[]> findScalarsByTelescopeAndStatus(@Param("telescope") Telescope telescope,
                                                   @Param("status") ReservationStatus status);

    /**
     * 悲观锁住某望远镜上指定状态的全部预订行。天气流程在已持有望远镜行锁后调用，
     * 使该望远镜上 ACTIVE 与 WEATHER_CANCELLED 预订的状态变更全局串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.telescope = :telescope and r.status = :status "
            + "order by r.startTime asc, r.id asc")
    List<Reservation> findByTelescopeAndStatusForUpdate(@Param("telescope") Telescope telescope,
                                                        @Param("status") ReservationStatus status);

    /** 按 ID 集合只读加载预订（关联立即加载），用于天气恢复响应中展示新预订详情。 */
    @Query("""
            select r from Reservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            where r.id in :ids
            """)
    List<Reservation> findDetailedByIds(@Param("ids") java.util.Collection<Long> ids);
}
