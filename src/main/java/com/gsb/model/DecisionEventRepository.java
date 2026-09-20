package com.gsb.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionEventRepository extends JpaRepository<DecisionEvent, Long> {

    List<DecisionEvent> findAllByOrderByIdAsc();

    List<DecisionEvent> findByRelatedExemptionIdOrderByIdAsc(Long relatedExemptionId);
}
