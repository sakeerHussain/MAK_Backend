# Vehicle Rental Backend

Spring Boot 4.1 / Java 21 API for the car-rental admin panel, covering fleet, drivers, employees, vendors, customers and quotes, bookings, invoices, maintenance, inspections, dashboard reporting, authentication, and notifications.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the module/layer folder structure, dependency rules, and guidance for adding modules.

## Persistent Windows development startup

Run `.\backend.cmd setup` once to save the database and optional SMTP credentials encrypted for your Windows account, outside this repository and OneDrive. You need a working PostgreSQL password; setup does not reset it. Then use `.\backend.cmd start` in any terminal, including after reboot. The launcher selects Java 21 and performs a clean Maven build before starting.

`.\backend.cmd check` validates the saved local configuration without starting the server or sending email. Reload VS Code once after setup so the project Java 21 settings take effect. See [LOCAL_SETUP.md](LOCAL_SETUP.md) for setup, email, storage, and verification details. The normal environment-variable workflow below remains available for deployment and other environments.

## Run locally using environment variables

1. Install Java 21 and PostgreSQL. Create a database named `car_rental` and a PostgreSQL user with access to it. For example, in `psql` as an administrator:

   ```sql
   CREATE USER car_rental WITH PASSWORD 'choose-a-password';
   CREATE DATABASE car_rental OWNER car_rental;
   ```

2. Set environment variables before starting the app. In PowerShell:

   ```powershell
   $env:DB_URL = 'jdbc:postgresql://localhost:5432/car_rental'
   $env:DB_USER = 'car_rental'
   $env:DB_PASSWORD = 'choose-a-password'
   $env:UPLOAD_DIR = 'C:\path\to\persistent\vehicle-uploads'
   ./mvnw.cmd spring-boot:run
   ```

   `UPLOAD_DIR` defaults to the backend's `uploads` directory. Keep that directory persistent and back it up alongside the database. Uploaded files are stored on disk; the database stores their metadata.

3. The API listens on `http://localhost:8080` and binds to loopback by default. Set `SERVER_ADDRESS` if a different bind address is needed. API access is protected by session authentication and role permissions; see [AUTH_SETUP.md](AUTH_SETUP.md). Flyway applies the versioned migrations on startup. Hibernate validates the schema instead of modifying it. Restart the backend after adding a new migration.

Run checks with `./mvnw.cmd test`. The test profile uses an in-memory H2 database and applies the same migrations.

## Fleet API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/vehicles` | Paged fleet list; optional `search`, `status`, `type`, `onSite`, `page`, `size` query parameters |
| GET | `/api/vehicles/summary` | Total and status counts |
| GET | `/api/vehicles/{id}` | Vehicle details, images, documents, and document status |
| POST | `/api/vehicles` | Add vehicle |
| PUT | `/api/vehicles/{id}` | Edit vehicle |
| PATCH | `/api/vehicles/{id}/status` | Set status, body such as `{ "status": "In Use" }` |
| GET | `/api/vehicles/import/template` | Download CSV template |
| POST | `/api/vehicles/import` | Import an array of validated vehicle records; returns success/failure rows and downloadable `errorReportCsv` content |
| POST | `/api/vehicles/{id}/images` | Upload one image as multipart `file`; at most eight images per vehicle |
| POST | `/api/vehicles/{id}/documents` | Upload multipart `file` with `type` and optional `expiryDate` (`yyyy-MM-dd`) |
| GET | `/api/vehicles/{id}/files/{fileId}` | Download an image or document |
| DELETE | `/api/vehicles/{id}/files/{fileId}` | Remove an image or document |

Documents support `Istimara`, `Insurance`, `Fahas`, and `Others`. The first three require an expiry date. Uploads accept PNG, JPG, or WebP; documents also accept PDF. Each file is limited to 5 MB. The document indicator is `Complete` only when the first three document types are present and unexpired; otherwise it is `Missing` or `Expired`.

The vehicle JSON request has fields matching the add-car form. A minimal example:

```json
{
  "make": "Toyota",
  "model": "Camry",
  "registration": "RNT-9001",
  "year": 2024,
  "vehicleId": "VH-9001",
  "type": "Sedan",
  "seats": 5,
  "transmission": "Automatic",
  "fuelType": "Hybrid",
  "dailyRate": 72.00,
  "rateCurrency": "USD",
  "ownership": "Owned",
  "status": "Available",
  "onSite": true,
  "listedForBooking": true,
  "showOnPublicSite": false
}
```

The existing frontend parses CSV/Excel and previews rows. Its import adapter should map each accepted row into the same JSON shape before calling `/api/vehicles/import`; the backend currently accepts JSON rows, not a raw Excel workbook. Registration number and vehicle ID are checked for duplicates. Error rows include their source row number, and `errorReportCsv` is ready to save as a `.csv` file.

## Drivers API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/drivers` | Paged roster; optional `search`, `category`, `status`, `page`, `size` |
| GET | `/api/drivers/{id}` | Driver profile, photo, documents, certifications, and history counters |
| POST | `/api/drivers` | Add driver |
| PUT | `/api/drivers/{id}` | Edit driver |
| PATCH | `/api/drivers/{id}/status` | Activate/deactivate, body such as `{ "status": "Inactive" }` |
| DELETE | `/api/drivers/{id}` | Remove from the roster by archiving; rejects drivers with booking/payment history |
| POST | `/api/drivers/import` | Import up to 2,000 JSON driver records with row-level success/failure report |
| POST | `/api/drivers/{id}/photo` | Upload one PNG/JPG/WebP profile photo as multipart `file` |
| POST | `/api/drivers/{id}/documents` | Upload multipart `file` with `type` and optional `expiryDate` |
| POST | `/api/drivers/{id}/certifications` | Add a certification with `name`, optional `issueDate`, `expiryDate`, and optional `file` |
| PATCH | `/api/drivers/{id}/certifications/{fileId}` | Edit certification metadata |
| GET / DELETE | `/api/drivers/{id}/files/{fileId}` | Download or remove an attachment or certification |

Driver documents support Iqama, Passport copy, License copy, Contract copy, PCC, Medical, Home address Proof, and Others. Iqama and License copy need an expiry date. Certifications can be saved without a file. Files are limited to 5 MB and stored under `UPLOAD_DIR/drivers`.

The browser parses CSV/Excel and sends validated JSON to the import endpoint. Employee ID and normalized phone number are checked for duplicates. The backend returns each row's result and `errorReportCsv` content. Archived drivers stay in the database but are hidden from the roster; their identifiers remain reserved.

## Current scope

Fleet, Driver, Vendor, Customer and Quote persistence are implemented. Booking-backed fleet utilization, assignment rules, user authentication/authorization, and the other admin modules need their own domain models and APIs. Driver booking/payment counts stay zero until those backend modules are connected. Keep this API local until authentication and authorization are added.

## Customers and quotes API

Restart the backend to apply Flyway migration `V4__customers_quotes.sql` to the configured PostgreSQL database. The migration creates `customers`, `customer_quotes`, and `quote_evidence`; it does not seed preview customers or quotations.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/customers` | Paged directory; optional `search`, `type`, `status`, `page`, `size` (maximum 100) |
| GET | `/api/customers/{id}` | Customer account |
| POST / PUT | `/api/customers` / `/api/customers/{id}` | Create or edit an Individual or Corporate account |
| PATCH | `/api/customers/{id}/status` | Activate/deactivate with `{ "status": "Inactive" }` |
| GET | `/api/quotes` | Paged quotes for the directory and booking rate selectors |
| GET | `/api/quotes/{id}` | Quote with its stored rate rows and approval |
| POST | `/api/customers/{id}/quotes` | Create a draft or shared quote; revision is assigned by the server |
| PUT | `/api/quotes/{id}` | Edit a Draft/Shared quote |
| POST | `/api/quotes/{id}/approve` | Record `by`, `date`, `method`, and `note` |
| POST | `/api/quotes/{id}/reject` | Reject a Shared quote |
| POST | `/api/quotes/{id}/supersede` | Retire an Approved quote without changing its rates |
| POST / GET | `/api/quotes/{id}/evidence` | Upload/download approval evidence (multipart `file`, PDF/PNG/JPEG, maximum 5 MB) |

Customer requests use `type`, `name`, `phone`, `email`, `address`, and `status`. Corporate requests also include registration/tax numbers, billing contact/address/city/country, payment terms, and waiting defaults. Corporate responses expose `billingPhone` and `billingEmail` for the existing company forms.

Quote requests contain metadata, `routes`, `monthly`, `services`, and `extras`. Rate rows retain their directed route and ordered stop definitions. Quote rates are stored as versioned JSON documents alongside queryable customer, status, date, and approval columns. Shared quotes require complete positive rates and valid dates. Approval checks expiry and prevents overlapping approved effective periods for one customer. Approved rates and evidence are immutable; create a revision to change them. Deactivation preserves account history.

The React Customer page and company forms use these APIs and reload customers/quotes on refresh. Bookings, corporate trips, quote snapshots, invoice drafts, issued invoices, and payment entries are also persisted through the APIs below. Prior browser demo records are not automatically copied into the database; an empty list after first startup is expected.

## Booking and invoice APIs

Flyway migration V5 adds rental records, booking payment entries, invoices, unique invoice source links, and invoice payment entries. Common booking columns are indexed for customer, status, vehicle/driver, and period; the complete trip details and agreed quote snapshot are retained in a JSON document. Existing frontend form field names are used in request/response bodies.

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/api/bookings` | List/create individual bookings |
| GET / PUT | `/api/bookings/{id}` | Read/edit a booking |
| GET / POST | `/api/corporate-trips` | List/create corporate trips |
| GET / PUT | `/api/corporate-trips/{id}` | Read/edit a corporate trip |
| PATCH | `/api/bookings/{id}/status`, `/api/corporate-trips/{id}/status` | Change status with `{ "status": "Confirmed" }` |
| PATCH | `/api/bookings/{id}/assignment`, `/api/corporate-trips/{id}/assignment` | Assign `vehicleId`, `driverId`, and optional `driverMode`; `handedOver: true` starts the rental |
| POST | `/api/bookings/{id}/payments` | Record `kind: Payment/Refund` and `amount` before invoicing |
| GET / POST | `/api/invoices` | List/create invoice drafts; `?issue=true` validates and issues in one transaction |
| GET / PUT | `/api/invoices/{id}` | Read/edit a draft; `?issue=true` also issues it |
| POST | `/api/invoices/{id}/issue` | Issue a saved draft |
| POST | `/api/invoices/{id}/payments` | Record `kind`, `amount`, `date`, `method`, and `reference` on an issued invoice |

Quick rentals require a vehicle and can be handed over immediately. Advance rentals start without a specific vehicle/driver; assignments are permitted on the pickup day. Status progression is Pending → Confirmed → Active → Completed; Pending/Confirmed bookings can be cancelled. Returning a vehicle restores its availability. Conflicts are checked across individual and corporate bookings, including overdue active rentals.

Corporate requests preserve project details, passengers, itinerary stops, waiting policy, and charges. The server checks capacity, required contact details, and recalculates waiting charges and tax. Approved fixed-route quote rates, extras, and tax are checked against the customer and pickup date; a historical snapshot remains valid when its quote is superseded.

Invoices require completed sources. An individual invoice uses one booking; a corporate invoice can consolidate trips for one company. A unique database constraint prevents a source from appearing on two invoices, including drafts. Invoice numbering and issue timestamps are assigned by the server. Issued invoice details are locked. The server calculates line discounts, VAT, and totals, applies existing booking payments once as an opening balance, and rejects overpayments/excess refunds. Subsequent individual invoice payments also update the linked booking balance.

Restart Spring Boot after applying source changes; Flyway creates the new tables on startup using the existing PostgreSQL configuration. The frontend development server continues using its existing `/api` proxy. List endpoints return arrays for the current frontend filtering and exports. Download returns a server-generated PDF. Invoice email is queued automatically when issued; booking confirmations, edits, assignments and status updates are queued when the email notification checkbox is enabled. Regulatory tax-document integration remains separate.

Run `mvn test` to validate migrations and customer/quote lifecycle tests using the isolated H2 test database. Production PostgreSQL credentials are not used by tests.


## SMTP email and PDF downloads

- `GET /api/invoices/{id}/pdf`: direct PDF attachment, including marked drafts.
- `POST /api/invoices/{id}/email`: queue an issued invoice PDF or resend after the earlier email was accepted. An outstanding message is reused to prevent duplicate clicks.
- `GET /api/emails?kind=Individual|Corporate|Invoice&recordId=123`: configuration flag and delivery history.
- `POST /api/emails/{id}/retry`: requeue a failed message.

Flyway V6 creates a durable email outbox. SMTP delivery happens after the transaction commits, polls every 15 seconds, and retries with backoff up to five attempts. A record lock prevents simultaneous workers from sending the same queued entry. Sent means accepted by the SMTP server, not guaranteed inbox delivery. A crash after SMTP acceptance but before database commit can result in a duplicate (at-least-once delivery). Messages retain a PDF snapshot so retries send the same document. No real emails are sent by tests.

SMTP is disabled by default. See EMAIL_SETUP.md. While disabled, requested messages remain queued; enabling SMTP will deliver that backlog. Email toggles stop future booking notifications, not already queued messages.

## Employees and maintenance

Flyway V7 and V8 add employee records, employee uploads, maintenance jobs and expense lines. Restart the backend to apply these migrations to the configured database. Existing preview records are not imported automatically.

Employees use `/api/employees` for paged lists and CRUD, `/api/employees/{id}/status` for activation, and `/api/employees/import` for validated bulk imports with row-level results. Multipart `/api/employees/save` and `/api/employees/{id}/save` save form details, photo and documents atomically. Stored files are available through employee file endpoints. Employee IDs and normalized phone numbers are unique; payroll history prevents deletion. Vendor-linked employees also protect their vendor from deletion or renaming.

Maintenance uses `/api/maintenance` and `/api/maintenance/{id}` for lists, details and multipart saves. Expense receipts are downloaded from `/api/maintenance/{id}/expenses/{lineId}/receipt`. Expense totals are calculated by the server in SAR. Uploads accept PDF/JPEG/PNG/WebP up to 5 MB; photos accept images only. Files created by a failed transaction are cleaned up, while replaced files are deleted after commit.

Open and In Progress jobs put a vehicle Under Maintenance. Closing the last open job restores its previous status. Active rentals prevent opening maintenance work, and open maintenance prevents vehicle assignment. Completed visits require an odometer reading and reset only explicitly selected General service, Oil, Tyres or Brakes counters; historical readings never reduce current odometer or reset baselines. Completed and Cancelled jobs are locked against editing.

The React Employee and Maintenance pages use these APIs, including imports, uploads, receipt downloads and fleet refresh after maintenance changes. Tests use isolated H2 databases and temporary uploads; they do not change production PostgreSQL data.

## Inspections API

Flyway `V9__inspections.sql` adds inspection records and photo metadata. Restart Spring Boot to apply it to the configured PostgreSQL database. No sample inspections are seeded.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/inspections` | Inspection log, newest inspection date first |
| GET | `/api/inspections/{id}` | Inspection details and stored photo URLs |
| POST | `/api/inspections` | Create an inspection; server assigns its INS reference |
| PUT | `/api/inspections/{id}` | Edit an inspection and retain/remove/add photos |
| GET | `/api/inspections/{id}/photos/{key}` | Read an image belonging to this inspection |

POST/PUT accept JSON or multipart requests. Multipart uses a JSON `record` part and photo parts named `photo0` through `photo9`; the record's `photos` array supplies `{ "uploadKey": "photo0" }` for a new upload or `{ "id": "stored-key" }` to retain an existing photo. Omitted photos are removed after a successful commit. Form details and uploads are saved in one transaction; failed batches clean up newly written files. Photos accept PNG/JPEG/WebP only, up to 5 MB each and ten per inspection. The request-size limit is 55 MB to support all ten photos.

Required fields are vehicleId, type, condition, inspectionDate, inspector, odometerKm, fuelLevel and photos (an empty array is allowed). bookingId and notes are optional. Types and conditions follow the React dropdowns. Odometer readings must be whole nonnegative kilometres, fuel must be 0/25/50/75/100 percent, and a linked individual or corporate rental must be assigned to the selected vehicle. Photo keys from another inspection cannot be reused.

Saving an inspection updates the fleet's current odometer only when the reading is higher. It does not reset service counters or change vehicle availability. The React page loads records from the API, preserves its filters and detail view, and refreshes fleet readings after saving. Run `mvn test` and frontend `npm test` for isolated regression checks; tests do not modify the production PostgreSQL database.


## Authentication and permissions

Flyway V10 adds account registration, approval, roles, password reset records and security audit events. Application APIs now require a valid session and module permissions. Create the initial administrator using the setup-code workflow in [AUTH_SETUP.md](AUTH_SETUP.md), then approve subsequent signups in Staff & Users. Existing business data is retained; no default login or sample users are created. SMTP configuration controls password-recovery emails.

### Live dashboard

GET `/api/dashboard?days=7` requires `dashboard.read`. Supported periods are 7, 30 and 90 days. Each section also respects the relevant module read permissions; invoice metrics are omitted for users without invoice access. The response includes current active rentals, fleet availability, pickups today, pending/open items, period pickup activity and the latest six bookings. KSA reporting dates use Asia/Riyadh. Activity excludes cancelled bookings and groups records (including the completed subset) by scheduled pickup date. Current KPI counts remain current snapshots when the chart period changes. No migration is required; existing tables supply these figures.

Dashboard extensions: `operations` includes confirmed handovers today, active returns today, active rentals past their scheduled return time, and confirmed Advance assignments within the next seven calendar dates (today plus six days). Self-drive does not require a driver. The queue shows the 12 highest-priority records. `financial` is absent without invoices.read: issued invoice value and net invoice-ledger collections use the selected period, while outstanding and overdue balances cover all current issued invoices. Opening-balance transfers, drafts, voids and pre-invoice booking deposits are excluded from net invoice collections. `alerts` respects module permissions and lists up to 12 expired/due/review/expiring items. Driver expiry checks cover active, non-archived drivers; document warnings extend through today + 30 days. Odometer maintenance needs a saved interval and last replacement/service reading; latest completed service dates also supply due alerts. Inspection alerts use the latest recorded Poor/Damaged condition and are not a repair-resolution workflow. No new migration is required.

## Booking notifications (SSE)

Migration V11 adds persistent booking alerts, per-account read/acknowledgement records and a durable event cursor. Individual and corporate booking writes create notification records in the same transaction; rolled-back changes never produce a visible alert. The notification APIs require the relevant booking write permission and a recipient record. Current recipients are active Admin, Manager and Operations accounts; supervisor escalations are delivered to active Admin/Manager accounts. Newly activated eligible accounts receive unresolved reminders on the next scan, not old informational history.

The admin bell opens recent notifications and the notification centre. The centre supports priority, alert type, individual/corporate, dates, unread, unresolved and unacknowledged filters, plus pagination. Critical warnings persist until the recipient acknowledges them or the underlying booking condition resolves. Acknowledgement does not change booking status or remove the unresolved attention item. Booking links open the existing detail/assignment workflow. Sound is an optional per-browser setting; critical warnings cannot be disabled.

Defaults (environment overrides):

- `NOTIFICATION_SCAN_MS=30000`: server reminder checks even when no staff browser is open.
- `NOTIFICATION_DISPATCH_MS=2000`: push committed cursor changes to connected SSE clients.
- `NOTIFICATION_PICKUP_MINUTES=60`: pickup and urgent assignment threshold.
- `NOTIFICATION_CONFIRMATION_MINUTES=120`: pending confirmation threshold.
- `NOTIFICATION_ESCALATION_MINUTES=15`: unacknowledged critical warnings escalate once to supervisors.
- `NOTIFICATION_EMAIL_ESCALATION=false`: optional email fallback. Set true only with the existing `MAIL_ENABLED=true` and SMTP settings. Uses the existing email queue/retries. Obsolete escalation emails and recipients whose supervisor access was removed are cancelled before sending.

Booking times are interpreted as Saudi local time (`Asia/Riyadh`). Advance assignments are flagged the previous day for planning, again on the pickup day, and urgently within the pickup threshold. The existing assignment rule still permits assignment only on the trip day. Self-drive requires a vehicle only. Rescheduling, cancellation, handover and return changes resolve obsolete reminders; resolved alerts remain available in history.

`GET /api/notifications/stream` uses authenticated session cookies, SSE ready/changed events, heartbeat comments and a five-minute connection lifetime followed by reconnect. Each connection checks account/session validity before dispatch. Reconnecting clients reload persisted notifications; the event ID is a change cursor, not proof that a person saw an alert. Failed pushes remain recoverable because the database is authoritative. The frontend also reloads every 30 seconds as a recovery fallback. Read/acknowledgement POSTs use the existing CSRF protection. Cross-origin production deployment must allow credentialed requests and session cookies; prefer the existing same-origin `/api` proxy.

For a production reverse proxy, disable buffering and caching on `/api/notifications/stream` and allow an idle read timeout longer than the heartbeat interval. Response headers include `Cache-Control: no-cache, no-transform` and `X-Accel-Buffering: no`. Do not expose the stream publicly. Multiple backend instances use a database lock for alert deduplication and poll the durable account cursor for local SSE connections; no in-memory-only delivery dependency is required.

In-app notifications require a connected browser. Browser/OS push and SMS are not included. Email delivery still depends on configured SMTP. Delivery failures are logged/retried by the existing email queue; notifications do not promise guaranteed human acknowledgement. Alert/event history currently has no automatic retention purge; establish a retention policy before long-term production use.


## Initial administrator during deployment

For a fresh database, optionally configure these backend environment variables in the deployment secret manager:

| Variable | Value |
| --- | --- |
| `ADMIN_SEED_ENABLED` | `true` for initial setup; defaults to `false` |
| `ADMIN_SEED_NAME` | Administrator display name |
| `ADMIN_SEED_EMAIL` | Administrator sign-in email |
| `ADMIN_SEED_PASSWORD` | Private initial password: 12+ characters, letters, numbers and symbols; maximum 72 UTF-8 bytes |

Start the backend normally. After migrations, the seed acquires the same database lock as first-admin registration. On an empty, uninitialized database it creates one Active Admin with a BCrypt password and an ADMIN_SETUP audit event, and completes bootstrap in one transaction. No bootstrap code or signup is required for this route. No email is sent by the seed. Sign in with the supplied email and password.

Invalid or missing credentials on a fresh database stop startup with a configuration error. Existing accounts with an incomplete setup flag also stop seeding for manual review; they are never promoted or overwritten. Once setup is complete, later runs skip seeding, even if the configured email/password changes or seed credentials are removed. Multiple instances serialize through the database lock.

After verifying initial sign-in, set `ADMIN_SEED_ENABLED=false` and remove `ADMIN_SEED_PASSWORD` from the deployment environment. Never commit credentials or place them on command lines. Keep normal deployment/application traffic behind readiness checks until startup completes. The existing `AUTH_BOOTSTRAP_CODE` signup process remains available when seeding is disabled. Use invitations for subsequent users; this seed is not an account-recovery or password-reset mechanism.
