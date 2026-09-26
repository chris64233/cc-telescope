package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.OpportunityProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OpportunityProposalRepository extends JpaRepository<OpportunityProposal, Long> {

    Optional<OpportunityProposal> findByCode(String code);

    /** 仅取 ID（不把实体加载进持久化上下文），用于规范加锁顺序中先定位账户再加行锁。 */
    @Query("select o.id from OpportunityProposal o where o.code = :code")
    Optional<Long> findIdByCode(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OpportunityProposal o where o.code = :code")
    Optional<OpportunityProposal> findByCodeForUpdate(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OpportunityProposal o where o.id = :id")
    Optional<OpportunityProposal> findByIdForUpdate(@Param("id") Long id);
}
