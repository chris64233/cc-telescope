package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.WeatherRecovery;
import com.chris64233.cc.telescope.domain.WeatherRecoveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WeatherRecoveryRepository extends JpaRepository<WeatherRecovery, Long> {

    /** 按主键悲观锁定单条恢复资格（取消/抢占由资格出资的新预订时使用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WeatherRecovery w where w.id = :id")
    Optional<WeatherRecovery> findByIdForUpdate(@Param("id") Long id);

    /**
     * 按原预订悲观锁定其全部恢复资格代（同一预订经窗口缩小再扩大可能产生多代），按 ID 升序。
     * 写事务取最新一代（ID 最大）操作；调用方须已持有望远镜行锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WeatherRecovery w where w.originalReservation.id = :reservationId order by w.id asc")
    List<WeatherRecovery> findAllByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    /** 只读详情查询：立即加载天气事件与原预订关联，保证事务外可安全访问。按 ID 升序。 */
    @Query("""
            select w from WeatherRecovery w
            join fetch w.weatherEvent
            join fetch w.originalReservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            left join fetch r.weatherEvent
            where r.id = :reservationId
            order by w.id asc
            """)
    List<WeatherRecovery> findDetailedByReservationId(@Param("reservationId") Long reservationId);

    /** 某天气事件下全部恢复资格（关联立即加载，供事件详情查询）。 */
    @Query("""
            select w from WeatherRecovery w
            join fetch w.originalReservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            left join fetch r.weatherEvent
            where w.weatherEvent.id = :eventId
            order by w.id asc
            """)
    List<WeatherRecovery> findDetailedByEventId(@Param("eventId") Long eventId);

    /**
     * 悲观锁住某天气事件下的恢复资格行（范围调整前在望远镜行锁内调用，
     * 与恢复排期/取消按恢复资格行串行化）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WeatherRecovery w where w.weatherEvent.id = :eventId order by w.id asc")
    List<WeatherRecovery> findByEventIdForUpdate(@Param("eventId") Long eventId);

    /**
     * 加锁前的标量定位（不把预订实体加载进一级缓存）：某事件下处于指定状态的恢复资格
     * 对应的原预订 ID。范围调整据此决定需要加锁的预订行。
     */
    @Query("""
            select w.originalReservation.id from WeatherRecovery w
            where w.weatherEvent.id = :eventId and w.status = :status
            """)
    List<Long> findOriginalReservationIds(@Param("eventId") Long eventId,
                                          @Param("status") WeatherRecoveryStatus status);

    @Query("""
            select w from WeatherRecovery w
            join fetch w.originalReservation r
            left join fetch r.proposal
            left join fetch r.opportunity
            join fetch r.telescope
            where w.status = :status
            order by w.id asc
            """)
    List<WeatherRecovery> findDetailedByStatus(@Param("status") WeatherRecoveryStatus status);
}
