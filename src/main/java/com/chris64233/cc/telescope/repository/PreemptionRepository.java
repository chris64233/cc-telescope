package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.Preemption;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PreemptionRepository extends JpaRepository<Preemption, Long> {

    Optional<Preemption> findByBusinessKey(String businessKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Preemption p where p.businessKey = :businessKey")
    Optional<Preemption> findByBusinessKeyForUpdate(@Param("businessKey") String businessKey);
}
