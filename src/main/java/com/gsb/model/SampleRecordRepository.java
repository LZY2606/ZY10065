package com.gsb.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SampleRecordRepository extends JpaRepository<SampleRecord, Long> {

    Optional<SampleRecord> findByDedupKey(String dedupKey);

    List<SampleRecord> findBySampleSetOrderByIdAsc(String sampleSet);

    List<SampleRecord> findAllByOrderByIdAsc();

    long countBySampleSet(String sampleSet);

    void deleteBySampleSet(String sampleSet);
}
