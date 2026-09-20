package com.gsb.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExemptionRepository extends JpaRepository<Exemption, Long> {

    Optional<Exemption> findByExemptionKey(String exemptionKey);

    List<Exemption> findByCandidateKeyOrderByIdAsc(String candidateKey);

    List<Exemption> findAllByOrderByIdAsc();
}
