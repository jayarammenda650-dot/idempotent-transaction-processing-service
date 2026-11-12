package com.assessment.repository;

import com.assessment.entity.TransactionEntity;
import com.assessment.enums.TransactionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionEntityRepository extends JpaRepository<TransactionEntity, Long> {

    Optional<TransactionEntity> findByExternalRequestId(String externalRequestId);

    @EntityGraph(attributePaths = "account")
    Optional<TransactionEntity> findByTransactionId(String transactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionEntity t where t.transactionId = :transactionId")
    Optional<TransactionEntity> findByTransactionIdForUpdate(@Param("transactionId") String transactionId);

    Optional<TransactionEntity> findByIdempotencyKey(String idempotencyKey);

    List<TransactionEntity> findByAccount_IdAndSequenceNumber(Long accountId, Long sequenceNumber);

    @EntityGraph(attributePaths = "account")
    List<TransactionEntity> findByAccount_IdAndStatusOrderBySequenceNumberAsc(Long accountId, TransactionStatus status);

    Optional<TransactionEntity> findTopByAccount_IdAndStatusOrderBySequenceNumberDesc(
            Long accountId,
            TransactionStatus status);

    List<TransactionEntity> findByStatus(TransactionStatus status);
}
