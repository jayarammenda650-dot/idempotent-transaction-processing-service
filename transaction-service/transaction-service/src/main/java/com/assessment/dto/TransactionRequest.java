package com.assessment.dto;

import com.assessment.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionRequest {

    @Size(max = 128, message = "Transaction id must be at most 128 characters")
    private String transactionId;

    @NotBlank(message = "External request id is required")
    private String externalRequestId;

    @NotBlank(message = "Account number is required")
    private String accountNumber;

    @NotBlank(message = "Currency is required")
    private String currency;

    @NotNull(message = "Transaction type is required")
    private TransactionType transactionType;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    private String counterpartyAccountNumber;

    @NotNull(message = "Sequence number is required")
    @Positive(message = "Sequence number must be positive")
    private Long sequenceNumber;

    private String idempotencyKey;

    private String correlationId;
}
