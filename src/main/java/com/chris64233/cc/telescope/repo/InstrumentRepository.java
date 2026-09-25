package com.chris64233.cc.telescope.repo;

import java.util.Optional;

import com.chris64233.cc.telescope.domain.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByName(String name);
}
