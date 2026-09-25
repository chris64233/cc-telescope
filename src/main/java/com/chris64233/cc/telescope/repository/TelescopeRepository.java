package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.Telescope;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TelescopeRepository extends JpaRepository<Telescope, Long> {

    Optional<Telescope> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Telescope t where t.code = :code")
    Optional<Telescope> findByCodeForUpdate(@Param("code") String code);
}
