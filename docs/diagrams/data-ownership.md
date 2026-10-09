# Data ownership

For report §3.4. Each table below was taken from the JPA mappings on `main` (`@Entity`,
`@Table`, `@Column`) and each schema name from the service's `application.yml`
(`jdbc:h2:mem:<schema>`). The ER diagram is [`database.puml`](database.puml)
([SVG](svg/database.svg)).

Every schema is H2 in-memory, and Hibernate generates it at start-up (`ddl-auto: create-drop`).
No service reads another service's schema. Data moves between services only as Kafka events
or, for the concierge, as REST responses.

| Service | Schema | Table | JPA class (kind) | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `event-catalog-service` | `catalogdb` | `events` | `Event` (aggregate root) | `Venue` and `DateRange` are embedded as `venue_*` and `starts_on` / `ends_on` columns |
| | | `sessions` | `Session` (entity inside `Event`) | FK `event_id` → `events`. `Track`, `TimeSlot`, `SeatCapacity` and `Price` are embedded |
| | | `speakers` | `Speaker` (aggregate root) | `sessions.speaker_id` refers to it by id only, with no FK |
| `registration-service` | `regdb` | `registrations` | `Registration` (aggregate root) | `AttendeeRef` and `Money totalAmount` are embedded. `refund_requested_at` is set while a refund request is open. The card token is **not** stored |
| | | `seat_holds` | `SeatHold` (entity inside `Registration`) | FK `registration_id`, ordered by `hold_index` |
| | | `waitlists` | `Waitlist` (aggregate root) | Primary key `session_id`: one waitlist per session, referred to by id with no FK. `@Version` |
| | | `waitlist_entries` | `WaitlistEntry` (entity inside `Waitlist`) | FK `waitlist_session_id`, ordered by `entry_index`. Holds an `attendee_id` only |
| | | `session_views` | `SessionView` (read model) | Local replica of catalogue sessions, fed by `session-added` and `session-capacity-changed`. `capacity` is null until the capacity event arrives. `@Version` |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `event-published`, `payment-succeeded`, `payment-failed`, `refund-issued`, `refund-declined` and `seats-released` |
| `payment-service` | `paydb` | `payment_orders` | `PaymentOrder` (aggregate root) | `registration_id` is unique, so a registration is charged at most once. `first_session_starts_at` feeds the refund policy. `@Version`, so one refund per order holds under a race |
| | | `payment_attempts` | `PaymentAttempt` (entity inside `PaymentOrder`) | FK `payment_order_id`, ordered by `attempt_index`. Stores the opaque `card_token` |
| | | `refunds` | `Refund` (aggregate root) | One decision about one order: `ISSUED` or `DECLINED`, with `decision_code` and `decided_by`. Refers to `payment_orders` by id, with no FK |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `registration-requested` and `refund-requested` |
| `ticketing-service` | `ticketdb` | `tickets` | `Ticket` (aggregate root) | `ticket_code` is unique. One row per confirmed session |
| | | `check_ins` | `CheckIn` (entity inside `Ticket`) | FK `ticket_id`. At most one per ticket |
| | | `transfer_records` | `TransferRecord` (entity inside `Ticket`) | FK `ticket_id`, append-only |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `registration-confirmed` and `refund-issued` |
| `analytics-service` | *none* | — | — | Kafka Streams state stores, in memory and changelogged to Kafka: `reserved-seats`, `session-fill-rates`, `capacity-alerts-sent` (RT1), `gate-throughput`, `gate-alerts-sent` (RT2), `registration-funnel`. Rebuilt by replaying the topics on every start and read by interactive query |
| `concierge-service` | *none* | — | — | Each agenda or support request builds a temporary `AgendaTools` or `SupportTools` object from REST responses. The organisers' escalation queue (`InMemoryEscalationStore`) is a list in memory, lost on restart |

## Values that cross a schema boundary

Some identifiers and values are copied from one schema to another. They are carried by events,
so no foreign key or cross-database join exists anywhere.

| Copied into | From | Carried by |
| :--- | :--- | :--- |
| `regdb.session_views` (title, slot, price, capacity) | `catalogdb.sessions` | `session-added`, `session-capacity-changed` |
| `paydb.payment_orders.registration_id`, amount, hold expiry, first session start | `regdb.registrations` | `registration-requested` |
| `ticketdb.tickets.registration_id`, `session_id`, holder | `regdb.registrations`, `regdb.seat_holds` | `registration-confirmed` |
| `paydb.refunds` (the decision is billing's own; the request comes from registration) | `regdb.registrations` | `refund-requested` |
| `regdb.registrations.status` → `REFUNDED`; `ticketdb.tickets.status` → `VOID` | `paydb.refunds` | `refund-issued` |
| analytics state stores | every schema above, indirectly | the topics each topology reads |

## Notes against the specification

- `specs/1-technical-architecture.md` lists *Conversation memory* for `concierge-service`.
  Neither agent keeps chat memory: the only state is the per-request tools object described above
  and the in-memory escalation queue.
- The `processed_events` tables are not in the specification's data-ownership table. They are
  infrastructure, not domain data, and exist in every service that consumes events.
- `specs/3-domain-models.md` §3.3 gives `WaitlistEntry` an `AttendeeRef`; the table stores an
  `attendee_id` only, because promotion is an offer and the attendee supplies name and email when
  they register.
