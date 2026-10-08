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
| `registration-service` | `regdb` | `registrations` | `Registration` (aggregate root) | `AttendeeRef` and `Money totalAmount` are embedded. The card token is **not** stored |
| | | `seat_holds` | `SeatHold` (entity inside `Registration`) | FK `registration_id`, ordered by `hold_index` |
| | | `session_views` | `SessionView` (read model) | Local replica of catalogue sessions, fed by `session-added` and `session-capacity-changed`. The only `@Version` column in the system |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `event-published`, `payment-succeeded` and `payment-failed` |
| `payment-service` | `paydb` | `payment_orders` | `PaymentOrder` (aggregate root) | `registration_id` is unique, so a registration is charged at most once |
| | | `payment_attempts` | `PaymentAttempt` (entity inside `PaymentOrder`) | FK `payment_order_id`, ordered by `attempt_index`. Stores the opaque `card_token` |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `registration-requested` |
| `ticketing-service` | `ticketdb` | `tickets` | `Ticket` (aggregate root) | `ticket_code` is unique. One row per confirmed session |
| | | `check_ins` | `CheckIn` (entity inside `Ticket`) | FK `ticket_id`. At most one per ticket |
| | | `transfer_records` | `TransferRecord` (entity inside `Ticket`) | FK `ticket_id`, append-only |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `registration-confirmed` |
| `analytics-service` | *none* | — | — | Skeleton on `main`. From M3 its state will live in Kafka Streams state stores, rebuilt by replaying topics |
| `concierge-service` | *none* | — | — | Holds no state between requests. Each agenda request builds a temporary in-memory `AgendaTools` dossier from REST responses |

## Values that cross a schema boundary

Some identifiers and values are copied from one schema to another. They are carried by events,
so no foreign key or cross-database join exists anywhere.

| Copied into | From | Carried by |
| :--- | :--- | :--- |
| `regdb.session_views` (title, slot, price, capacity) | `catalogdb.sessions` | `session-added`, `session-capacity-changed` |
| `paydb.payment_orders.registration_id`, amount, hold expiry | `regdb.registrations` | `registration-requested` |
| `ticketdb.tickets.registration_id`, `session_id`, holder | `regdb.registrations`, `regdb.seat_holds` | `registration-confirmed` |

## Notes against the specification

- `specs/1-technical-architecture.md` lists `Waitlist`, `WaitlistEntry` (regdb) and `Refund`
  (paydb). They are FR-10 and FR-11, not on `main` yet.
- It lists *Conversation memory* for `concierge-service`. Agent 1 keeps no chat memory: the
  only state is the per-request dossier described above.
- The `processed_events` tables are not in the specification's data-ownership table. They are
  infrastructure, not domain data, and exist in every service that consumes events.
