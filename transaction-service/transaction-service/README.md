# Idempotent Transaction Processing Service

A Java 17 and Spring Boot service for validating, ordering, and processing account transactions while preventing duplicate balance and ledger effects. It implements the core behavior from the attached CTS practical FDE assessment using REST input and an H2 in-memory database.

This is a lightweight assessment implementation, not a production queue-backed background worker. See [DESIGN.md](DESIGN.md) for the architecture, lifecycle, constraints, and production recommendations.

## Problem Statement

Upstream transaction requests can arrive more than once, out of sequence, or be retried after transient failures. The service must validate requests, apply each accepted transaction once, maintain transaction state, avoid partial account/ledger updates, and expose processing results and summary counts.

The assessment permits a simple input mechanism and lightweight persistence, but expects a defined duplicate, ordering, and retry strategy. This implementation uses synchronous REST endpoints and H2 so the core processing behavior can be exercised without an external queue or database.

## Implemented Behavior

- Validates required request fields, positive amounts and sequences, supported currencies, and account/transaction currency agreement.
- Processes `CREDIT` and `ADJUSTMENT` as balance increases; processes `DEBIT` and `TRANSFER` as balance decreases.
- Serializes balance changes with an account row lock and writes the transaction, balance, and ledger entry within a database transaction.
- Detects duplicates using request ID, business transaction ID, and optional idempotency key. Duplicate attempts do not create another ledger entry or balance change.
- Orders batches per account. Transactions beyond a sequence gap remain `PENDING`; later batches re-evaluate and release pending transactions after the gap is filled. Stale sequences are `REJECTED`.
- Retries transient failures inline, up to `transaction.retry.max-retries` (default `3`). `TXN-2005` simulates one transient failure; `TXN-RETRY-EXHAUSTED` simulates repeated failure.
- Reports transaction and duplicate-attempt counts through the summary endpoint.

## Requirements and Current Limits

 The batch endpoint validates items independently. Invalid field values, unsupported or mismatched currencies, null items, and missing accounts are returned as per-item `FAILED` responses while valid siblings continue processing. Invalid batch responses are not persisted as transaction entities, so they are not included in the global summary or available through the transaction lookup endpoint. A malformed JSON document or invalid top-level batch (empty list or more than 500 items) still returns `400` for the whole request.

 The service processes requests synchronously in the web request. There is no queue, scheduler, worker pool, retry backoff, dead-letter queue, or recovery scan after process restart. H2 is in-memory, so all state is lost when the process exits. `PROCESSING` is set inside the same transaction that finishes processing and is not observable as a committed intermediate state. SLF4J logs record transaction status, retries, exhaustion, and rejected batch items using identifiers only; there are no metrics, tracing, or log correlation backend integrations.

## API

Base path: `/api/transactions`

### `POST /api/transactions/process`

Process one transaction. `transactionId` is optional; the service uses the first nonblank value in this order: `transactionId`, `idempotencyKey`, `externalRequestId`.

| Field | Requirement |
| --- | --- |
| `transactionId` | Optional; at most 128 characters. Falls back to `idempotencyKey`, then `externalRequestId`. |
| `externalRequestId` | Required request/replay identifier. Reuse is treated as a duplicate. |
| `accountNumber` | Required; the account must already exist. |
| `currency` | Required; must be supported and match the account currency. |
| `transactionType` | Required; `CREDIT`, `DEBIT`, `TRANSFER`, or `ADJUSTMENT`. |
| `amount` | Required and greater than zero. |
| `sequenceNumber` | Required and greater than zero. |
| `idempotencyKey` | Optional; unique when supplied. |
| `counterpartyAccountNumber` | Optional metadata; not used for a two-sided transfer in the current implementation. |
| `correlationId` | Optional tracing/correlation value. |

Request:

```json
{
  "transactionId": "TXN-2001",
  "externalRequestId": "REQ-2001",
  "accountNumber": "ACC-201",
  "currency": "USD",
  "transactionType": "CREDIT",
  "amount": 100.00,
  "sequenceNumber": 1,
  "idempotencyKey": "client-key-2001",
  "correlationId": "batch-77"
}
```

The account must already exist. For this example, assume `ACC-201` has a USD balance of `50.00`.

Successful response (`200 OK`):

```json
{
  "transactionId": "TXN-2001",
  "requestId": "REQ-2001",
  "status": "PROCESSED",
  "message": "Transaction processed successfully.",
  "balanceAfterProcessing": 150.0000,
  "sequenceNumber": 1,
  "processedAt": "2026-11-12T12:00:00"
}
```

Status mapping: `200` for processed, `409` for duplicate, and `422` for a persisted failed transaction. Request Bean Validation errors (for example, a negative amount or missing sequence) return `400` before processing. The controller reserves `202` for pending/processing/retry-pending statuses, but the current synchronous single-transaction path does not return those states.

### `POST /api/transactions/batch`

Submit 1 to 500 transactions. Items are validated and grouped by account, then processed in sequence order. The response is `200 OK` and includes each result.

Request:

```json
{
  "transactions": [
    {
      "transactionId": "TXN-2002",
      "externalRequestId": "REQ-2002",
      "accountNumber": "ACC-201",
      "currency": "USD",
      "transactionType": "DEBIT",
      "amount": 40.00,
      "sequenceNumber": 2
    },
    {
      "transactionId": "TXN-2001",
      "externalRequestId": "REQ-2001",
      "accountNumber": "ACC-201",
      "currency": "USD",
      "transactionType": "CREDIT",
      "amount": 100.00,
      "sequenceNumber": 1
    }
  ]
}
```

Response shape:

```json
{
  "transactions": [
    {
      "transactionId": "TXN-2001",
      "requestId": "REQ-2001",
      "status": "PROCESSED",
      "message": "Transaction processed successfully.",
      "balanceAfterProcessing": 100.0000,
      "sequenceNumber": 1,
      "processedAt": "2026-11-12T12:00:00"
    },
    {
      "transactionId": "TXN-2002",
      "requestId": "REQ-2002",
      "status": "PROCESSED",
      "message": "Transaction processed successfully.",
      "balanceAfterProcessing": 60.0000,
      "sequenceNumber": 2,
      "processedAt": "2026-11-12T12:00:01"
    }
  ],
  "totalCount": 2,
  "processedCount": 2,
  "duplicateCount": 0,
  "failedCount": 0
}
```

For this example, assume `ACC-201` starts at `0.00 USD`.

The per-item response includes `PENDING` results for sequence gaps. The batch counters only count processed, duplicate, and failed statuses.

### `GET /api/transactions/{transactionId}`

Returns the stored transaction with `200 OK`, or a structured `404` error if it does not exist. Duplicate attempts are recorded in the processed-request store; the original transaction remains in its original state.

### `GET /api/transactions/summary`

Returns a `ProcessingSummary`:

```json
{
  "totalRequests": 12,
  "receivedRequests": 0,
  "processingRequests": 0,
  "processedRequests": 8,
  "duplicateRequests": 2,
  "pendingRequests": 1,
  "retryPendingRequests": 0,
  "retryScheduledRequests": 0,
  "rejectedRequests": 0,
  "retryAttempts": 1,
  "outOfOrderRequests": 0,
  "failedRequests": 1,
  "generatedAt": "2026-11-12T12:05:00"
}
```

`totalRequests` includes persisted transaction records and recorded duplicate attempts. Status counters are separate; `retryAttempts` is an attempt count rather than a transaction-status count. Response-only invalid batch items are not persisted and therefore are not included.

### Error response

Validation and exception responses use `ApiErrorResponse`:

```json
{
  "timestamp": "2026-11-12T12:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed.",
  "path": "/api/transactions/process",
  "fieldErrors": {
    "amount": "Amount must be greater than zero."
  }
}
```

Duplicate processing responses use `TransactionResponse` with status `DUPLICATE`, not this error envelope.

## Build and Run

Prerequisites: Java 17 or later. Maven Wrapper scripts are included.

Windows PowerShell:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```sh
./mvnw clean test
./mvnw spring-boot:run
```

The server listens on `http://localhost:8080`. The development H2 console is at `http://localhost:8080/h2-console` with the JDBC URL `jdbc:h2:mem:transactiondb` and username `sa` (no password). Do not expose the H2 console outside a local development environment.

There is no account-creation API. Create or seed an account before submitting sample requests.

## Tests

Run the full suite with `./mvnw test` (or `mvnw.cmd test` on Windows). The integration and service tests cover validation, balance and ledger effects, duplicate handling, concurrent submissions, retry success/exhaustion, sequence ordering and gaps, summary counts, and REST responses.

## Project Layout

- `controller`: REST routes and HTTP status mapping.
- `dto`: validated request and response contracts.
- `entity`: accounts, transactions, ledger entries, and processed-request records.
- `repository`: Spring Data JPA persistence and locking queries.
- `service`: validation, ordering, idempotency, retry, and transaction orchestration.
- `exception`: business exceptions and global HTTP error mapping.
- `src/test`: API integration, service integration, and application tests.
