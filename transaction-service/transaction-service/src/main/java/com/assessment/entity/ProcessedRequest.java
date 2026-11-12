package com.assessment.entity;

import com.assessment.enums.TransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "processed_requests",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_processed_requests_hash", columnNames = "request_hash"),
                @UniqueConstraint(name = "uk_processed_requests_external_reference", columnNames = "external_reference")
        }
)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProcessedRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "request_hash", nullable = false, unique = true, length = 128)
    private String requestHash;

    @Column(name = "external_reference", nullable = false, unique = true, length = 128)
    private String externalReference;

    @Column(name = "transaction_id", nullable = false, length = 128)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionStatus status;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "retry_count")
    private Integer retryCount;

        @Builder.Default
        @Column(name = "duplicate_attempts", nullable = false)
        private Integer duplicateAttempts = 0;

    @Column(name = "raw_payload", length = 2000)
    private String rawPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
