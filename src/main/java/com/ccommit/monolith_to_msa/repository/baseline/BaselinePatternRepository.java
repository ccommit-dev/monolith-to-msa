package com.ccommit.monolith_to_msa.repository.baseline;

import com.ccommit.monolith_to_msa.domain.baseline.BaselinePattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Baseline 패턴 Repository
 */
@Repository
public interface BaselinePatternRepository extends JpaRepository<BaselinePattern, Long> {
    
    /**
     * 특정 엔드포인트의 최신 Baseline 패턴 조회
     */
    Optional<BaselinePattern> findFirstByEndpointOrderByLearnedAtDesc(String endpoint);
    
    /**
     * 특정 엔드포인트의 Baseline 패턴 조회
     */
    Optional<BaselinePattern> findByEndpoint(String endpoint);
}
