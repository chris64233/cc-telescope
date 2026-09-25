package com.chris64233.cc.telescope.repo;

import java.util.Optional;

import com.chris64233.cc.telescope.domain.Telescope;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelescopeRepository extends JpaRepository<Telescope, Long> {

    Optional<Telescope> findByCode(String code);
}
