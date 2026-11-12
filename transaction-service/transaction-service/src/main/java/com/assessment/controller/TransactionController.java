package com.assessment.controller;

import com.assessment.dto.TransactionRequest;
import com.assessment.dto.TransactionBatchRequest;
import com.assessment.dto.TransactionBatchResponse;
import com.assessment.dto.TransactionResponse;
import com.assessment.service.TransactionProcessingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionProcessingService transactionProcessingService;

    public TransactionController(TransactionProcessingService transactionProcessingService) {
        this.transactionProcessingService = transactionProcessingService;
    }

    @PostMapping("/batch")
    public ResponseEntity<TransactionBatchResponse> processBatch(
            @Valid @RequestBody TransactionBatchRequest request) {
        return ResponseEntity.ok(transactionProcessingService.processBatch(request));
    }

    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> processTransaction(
            @Valid @RequestBody TransactionRequest request) {
        TransactionResponse response = transactionProcessingService.processTransaction(request);
        return switch (response.getStatus()) {
            case PROCESSED -> ResponseEntity.ok(response);
            case DUPLICATE -> ResponseEntity.status(409).body(response);
            case FAILED -> ResponseEntity.unprocessableEntity().body(response);
            case RETRY_PENDING, RETRY_SCHEDULED, PENDING, PROCESSING -> ResponseEntity.accepted().body(response);
            default -> ResponseEntity.ok(response);
        };
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable String transactionId) {
        return ResponseEntity.ok(transactionProcessingService.getTransaction(transactionId));
    }

    @GetMapping("/summary")
    public ResponseEntity<com.assessment.dto.ProcessingSummary> getProcessingSummary() {
        return ResponseEntity.ok(transactionProcessingService.getProcessingSummary());
    }
}
