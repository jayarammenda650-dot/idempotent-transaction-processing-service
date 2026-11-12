package com.assessment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessingSummary {

    private long totalRequests;
    private long receivedRequests;
    private long processingRequests;
    private long processedRequests;
    private long duplicateRequests;
    private long pendingRequests;
    private long retryPendingRequests;
    private long retryScheduledRequests;
    private long rejectedRequests;
    private long retryAttempts;
    private long outOfOrderRequests;
    private long failedRequests;
    private LocalDateTime generatedAt;
}
