package com.assessment.dto;

import com.assessment.enums.TransactionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionResponse {

    private String transactionId;
    private String requestId;
    private TransactionStatus status;
    private String message;
    private BigDecimal balanceAfterProcessing;
    private Long sequenceNumber;
    private LocalDateTime processedAt;
    private ProcessingSummary processingSummary;
}
