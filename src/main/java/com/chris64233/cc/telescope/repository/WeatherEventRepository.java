package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.WeatherEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WeatherEventRepository extends JpaRepository<WeatherEvent, Long> {

    Optional<WeatherEvent> findByBusinessKey(String businessKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WeatherEvent w where w.businessKey = :businessKey")
    Optional<WeatherEvent> findByBusinessKeyForUpdate(@Param("businessKey") String businessKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WeatherEvent w where w.id = :id")
    Optional<WeatherEvent> findByIdForUpdate(@Param("id") Long id);
}
