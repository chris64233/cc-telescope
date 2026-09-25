package com.chris64233.cc.telescope.repo;

import java.util.Optional;

import com.chris64233.cc.telescope.domain.Proposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProposalRepository extends JpaRepository<Proposal, Long> {

    Optional<Proposal> findByProposalNo(String proposalNo);

    /**
     * 行级悲观锁：必须作为该提案在事务中的首次读取，保证并发预订时
     * 配额扣减串行化，杜绝超卖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Proposal p where p.proposalNo = :proposalNo")
    Optional<Proposal> findByProposalNoForUpdate(@Param("proposalNo") String proposalNo);
}
