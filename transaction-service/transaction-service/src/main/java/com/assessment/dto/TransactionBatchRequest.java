package com.assessment.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionBatchRequest {

    @NotEmpty(message = "At least one transaction is required")
    @Size(max = 500, message = "A batch may contain at most 500 transactions")
    private List<TransactionRequest> transactions;
}
