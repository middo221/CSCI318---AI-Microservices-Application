# API Endpoints Summary

Endpoints without a milestone marker are **implemented**. Markers `(M1)` … `(M4)` indicate the
milestone that delivers them, so this file doubles as a progress tracker.

Milestones: **M0** skeleton · **M1** catalogue and first event · **M2** the saga ·
**M3** streams · **M4** agents.

---

## 1. Event Catalog Service (`http://localhost:8081/api/v1`)

| Feature | Method | Endpoint Path | Request Body | Response (Success) | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **FR-01** | `POST` | `/events` | `CreateEventRequest` | `201 Created` (`EventResponse`) | Create a draft event |
| — | `GET` | `/events/{id}` | *None* | `200 OK` (`EventResponse`) | Get an event by id |
| **FR-02** | `POST` | `/events/{id}/sessions` | `CreateSessionRequest` | `201 Created` (`SessionResponse`) | Add a session to a draft event |
| **FR-04** | `PUT` | `/events/{id}/sessions/{sessionId}/capacity` | `UpdateCapacityRequest` | `200 OK` (`SessionResponse`) | Adjust a session's seat capacity |
| **FR-03** | `POST` | `/events/{id}/publish` | *None* | `200 OK` (`EventResponse`) | Publish an event, opening registration. Requires at least one session |
| **FR-05** | `GET` | `/events` | *None* | `200 OK` (`Array<EventResponse>`) | List events, filterable by `status`. Each carries its `sessions` |
| **FR-05** | `GET` | `/sessions/search` | *None* | `200 OK` (`Array<SessionResponse>`) | Search **published** sessions by `track`, `day`, `speaker`, `q`. `speaker` accepts an id or a name fragment |
| **FR-02** | `POST` | `/speakers` | `CreateSpeakerRequest` | `201 Created` (`SpeakerResponse`) | Create a speaker |
| **FR-02** | `GET` | `/speakers/{id}` | *None* | `200 OK` (`SpeakerResponse`) | Get a speaker by id |

**Error responses:** `400` (`VALIDATION_FAILED` or `INVALID_REQUEST`), `404`
(`EVENT_NOT_FOUND`, `SESSION_NOT_FOUND`, `SPEAKER_NOT_FOUND`), `409`
(`EVENT_ALREADY_PUBLISHED`, `SPEAKER_DOUBLE_BOOKED`).

---

## 2. Registration Service (`http://localhost:8082/api/v1`)

| Feature | Method | Endpoint Path | Request Body | Response (Success) | Inter-Service Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **FR-06** | `POST` | `/registrations` | `CreateRegistrationRequest` | `201 Created` (`RegistrationResponse`) | emits `registration-requested` *(M2)* |
| — | `GET` | `/registrations/{id}` | *None* | `200 OK` (`RegistrationResponse`) | None *(M2)* |
| — | `GET` | `/attendees/{attendeeId}/registrations` | *None* | `200 OK` (`Array<RegistrationResponse>`) | None *(M2)* |
| **FR-11** | `POST` | `/registrations/{id}/refund-requests` | `RefundRequest` | `202 Accepted` (`RefundRequestResponse`) | emits `refund-requested` *(M3)* |
| **FR-10** | `POST` | `/sessions/{sessionId}/waitlist` | `JoinWaitlistRequest` | `201 Created` (`WaitlistEntryResponse`) | emits `waitlist-joined` *(M3)* |
| **FR-06** | `GET` | `/sessions/{sessionId}/availability` | *None* | `200 OK` (`SessionAvailabilityResponse`) | reads local `SessionView`, replicated from catalog events. `reservedSeats` is always `0` until M2 introduces seat holds. `404` until `session-added` has arrived |

**Error responses:** `400`, `404` (`REGISTRATION_NOT_FOUND`, `SESSION_NOT_FOUND`), `409`
(`SESSION_FULL`, `REGISTRATION_NOT_PENDING`).

---

## 3. Payment Service (`http://localhost:8083/api/v1`)

| Feature | Method | Endpoint Path | Request Body | Response (Success) | Inter-Service Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **FR-07** | `GET` | `/payment-orders/{id}` | *None* | `200 OK` (`PaymentOrderResponse`) | None *(M2)* |
| **FR-07** | `GET` | `/registrations/{registrationId}/payment-order` | *None* | `200 OK` (`PaymentOrderResponse`) | None *(M2)* |
| **FR-11** | `GET` | `/refund-policy/quote` | *None* | `200 OK` (`RefundQuoteResponse`) | Query `registrationId`; used by the support agent *(M3)* |
| **FR-11** | `POST` | `/payment-orders/{id}/refunds` | `IssueRefundRequest` | `201 Created` (`RefundResponse`) | emits `refund-issued` *(M3)* |

**Error responses:** `400`, `404` (`PAYMENT_ORDER_NOT_FOUND`), `409` (`ALREADY_REFUNDED`).

---

## 4. Ticketing Service (`http://localhost:8084/api/v1`)

| Feature | Method | Endpoint Path | Request Body | Response (Success) | Inter-Service Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **FR-08** | `GET` | `/tickets/{ticketCode}` | *None* | `200 OK` (`TicketResponse`) | None *(M2)* |
| **FR-08** | `GET` | `/registrations/{registrationId}/tickets` | *None* | `200 OK` (`Array<TicketResponse>`) | None *(M2)* |
| **FR-12** | `POST` | `/tickets/{ticketCode}/transfer` | `TransferTicketRequest` | `200 OK` (`TicketResponse`) | emits `ticket-transferred` *(M3)* |
| **FR-13** | `POST` | `/gates/{gateId}/check-ins` | `CheckInRequest` | `201 Created` (`CheckInResponse`) | emits `attendee-checked-in` *(M2)* |

`CheckInRequest` carries `ticketCode` and `queuedAt`; `queuedAt` is required because the gate
congestion stream derives wait time from it.

**Error responses:** `400`, `404` (`TICKET_NOT_FOUND`), `409` (`TICKET_VOID`,
`ALREADY_CHECKED_IN`).

---

## 5. Analytics Service (`http://localhost:8085/api/v1`)

All responses are served from **Kafka Streams interactive queries**, not a database.

| Feature | Method | Endpoint Path | Request Body | Response (Success) | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **FR-14** | `GET` | `/analytics/sessions/{sessionId}/fill-rate` | *None* | `200 OK` (`FillRateResponse`) | Live fill rate for one session *(M3)* |
| **FR-15** | `GET` | `/analytics/sessions/at-risk` | *None* | `200 OK` (`Array<FillRateResponse>`) | Sessions at or above `threshold` (default 0.9) *(M3)* |
| **FR-16** | `GET` | `/analytics/gates/{gateId}/throughput` | *None* | `200 OK` (`GateThroughputResponse`) | Check-ins per minute and average wait *(M3)* |
| **FR-16** | `GET` | `/analytics/gates/congested` | *None* | `200 OK` (`Array<GateThroughputResponse>`) | Gates above the congestion threshold *(M3)* |
| — | `GET` | `/analytics/funnel` | *None* | `200 OK` (`FunnelResponse`) | Registration conversion and abandonment *(M3)* |

**Error responses:** `404` (`SESSION_NOT_TRACKED`, `GATE_NOT_TRACKED`), `503`
(`STORE_REBUILDING` — the state store is still restoring after a restart).

---

## 6. Concierge Service (`http://localhost:8086/api/v1`)

| Feature | Method | Endpoint Path | Request Body | Response (Success) | Inter-Service Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **FR-17** | `POST` | `/concierge/agenda` | `AgendaRequest` | `200 OK` (`AgendaResponse`) | REST to catalog, registration *(M4)* |
| **FR-18** | `POST` | `/concierge/support` | `SupportRequest` | `200 OK` (`SupportResponse`) | REST to registration, payment, ticketing *(M4)* |

`AgendaRequest` carries `attendeeId` and a free-text `interests` field.
`SupportRequest` carries `attendeeId` and a free-text `message`.
`SupportResponse` includes an `outcome` of `RESOLVED`, `REFUND_REQUESTED` or `ESCALATED`.

**Error responses:** `400`, `502` (`LLM_UNAVAILABLE`), `504` (`LLM_TIMEOUT`).

---

## Common Error Body

Every non-2xx response across all six services uses the same shape:

```json
{ "code": "EVENT_NOT_FOUND", "message": "no event with id no-such-event" }
```
