package com.chris64233.cc.telescope.repository;

import com.chris64233.cc.telescope.domain.PreemptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PreemptionRecordRepository extends JpaRepository<PreemptionRecord, Long> {

    Optional<PreemptionRecord> findByBusinessKey(String businessKey);

    List<PreemptionRecord> findByOpportunityCodeOrderByIdAsc(String opportunityCode);
}
