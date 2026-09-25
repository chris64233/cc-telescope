package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.Proposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProposalRepository extends JpaRepository<Proposal, Long> {

    Optional<Proposal> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Proposal p where p.code = :code")
    Optional<Proposal> findByCodeForUpdate(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Proposal p where p.id = :id")
    Optional<Proposal> findByIdForUpdate(@Param("id") Long id);
}
