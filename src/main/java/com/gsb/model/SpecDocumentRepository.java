package com.gsb.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecDocumentRepository extends JpaRepository<SpecDocument, Long> {

    Optional<SpecDocument> findBySpecKey(String specKey);

    List<SpecDocument> findAllByOrderBySpecKeyAsc();
}
