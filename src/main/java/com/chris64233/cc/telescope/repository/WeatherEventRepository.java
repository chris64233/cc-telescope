package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.WeatherEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WeatherEventRepository extends JpaRepository<WeatherEvent, Long> {

    Optional<WeatherEvent> findByBusinessKey(String businessKey);

    /** 事件行加锁：范围调整在望远镜行锁之后首次即加锁加载事件。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WeatherEvent e where e.businessKey = :businessKey")
    Optional<WeatherEvent> findByBusinessKeyForUpdate(@Param("businessKey") String businessKey);

    /** 仅取事件所在望远镜编号（不把实体加载进一级缓存），供范围调整先锁望远镜行。 */
    @Query("select e.telescopeCode from WeatherEvent e where e.businessKey = :businessKey")
    Optional<String> findTelescopeCodeByBusinessKey(@Param("businessKey") String businessKey);

    List<WeatherEvent> findByTelescopeCodeOrderByIdAsc(String telescopeCode);
}

