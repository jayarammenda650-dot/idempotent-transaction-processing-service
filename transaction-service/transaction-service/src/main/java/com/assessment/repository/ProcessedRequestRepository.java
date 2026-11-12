package com.assessment.repository;

import com.assessment.entity.ProcessedRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessedRequestRepository extends JpaRepository<ProcessedRequest, Long> {

    Optional<ProcessedRequest> findByRequestHash(String requestHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProcessedRequest p where p.requestHash = :requestHash")
    Optional<ProcessedRequest> findByRequestHashForUpdate(@Param("requestHash") String requestHash);

    Optional<ProcessedRequest> findByExternalReference(String externalReference);

    Optional<ProcessedRequest> findTopByExternalReferenceOrderBySequenceNumberDesc(String externalReference);

    Optional<ProcessedRequest> findByTransactionId(String transactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProcessedRequest p where p.externalReference = :externalReference")
    Optional<ProcessedRequest> findByExternalReferenceForUpdate(@Param("externalReference") String externalReference);
}
