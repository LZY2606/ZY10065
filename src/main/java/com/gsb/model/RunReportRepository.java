package com.gsb.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunReportRepository extends JpaRepository<RunReport, Long> {

    Optional<RunReport> findByRunKey(String runKey);

    List<RunReport> findAllByOrderByRunKeyAsc();
}
