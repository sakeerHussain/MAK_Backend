# Public website enquiry inbox

Public rental enquiries and Contact Us messages are now stored in the database (Flyway V22). These forms do not create customers, quotes, bookings, reservations or payments, and they do not send email. Staff contact the sender and review the request before using the existing customer/quote/booking workflows. Automated conversion remains a separate stage.

## Staff workflow

Open Admin > Trips & Customers > Enquiries (`#/enquiries`). Filter by Rental/Contact and status, or search sender name, email, company or subject. Review opens an inline detail region. Statuses: New, In progress, Closed, Spam; items may be reopened. Save changes records staff account, time, status transition and whether the internal note changed. Original public submission data remains unchanged. Concurrent edits return a conflict rather than silently overwriting another review. Times display in Asia/Riyadh (UTC+3); rental dates are KSA calendar dates.

Admin, Manager and Operations have View/Edit by default. Finance and Viewer have no inbox access by default. Custom roles expose enquiries.read and enquiries.edit under Website enquiries in additional modules. Edit requires View. There is no staff Create or Delete permission for submissions. Public callers cannot list messages, retrieve a message or access review history.

## Public forms and API

- POST /api/public/rental-enquiries
- POST /api/public/contact-messages
- GET /api/enquiries and GET /api/enquiries/{id} (enquiries.read)
- GET /api/enquiries/{id}/history (enquiries.read)
- PATCH /api/enquiries/{id} (enquiries.edit; status, internalNote, version)

Public POSTs use the existing anonymous CSRF session from GET /api/auth/csrf. The frontend shares the sessionFetch helper and respects VITE_API_BASE_URL. Public form requests suppress staff-session redirects and renew an expired CSRF token once with the same submission ID. Keep frontend/backend on the same site where possible. Existing CORS/session-cookie settings must match the deployment; do not disable CSRF or use wildcard credentialed CORS.

Public requests include a UUID submissionId. A retry with the same UUID and payload returns the same reference; changing the payload under an existing UUID returns 409. A unique DB constraint handles concurrent retries. The UI keeps entries on failure and only confirms success after receiving a persisted reference. It prevents duplicate clicks while sending. Selected vehicle IDs are checked for public visibility, and the vehicle name is snapshotted; its publication does not imply availability or a confirmed price. No original filename or compliance document is accepted through these forms.

Validation covers required sender fields, email, length limits, honeypot, KSA pickup date and return date order. The rental phone field is required. Server-controlled kind/status/receipt/time and internal review data cannot be set through public requests. Inbox output renders submitted content as plain text.

## Deployment

Apply V22 automatically through Flyway with the normal application database migration permissions. No email credentials are required for this inbox integration. PUBLIC_ENQUIRY_RATE_LIMIT defaults to 10 combined public submissions per 15-minute window per socket peer, per running instance. The limiter uses the peer address and does not trust arbitrary X-Forwarded-For headers. Behind a reverse proxy this may group callers under the proxy address: configure trusted client-address handling or an edge per-client rate limit and tune this setting for expected traffic. For multiple replicas use a shared/edge limiter; the in-memory limiter resets on restart. The form honeypot is basic protection, not a CAPTCHA service.

Submissions and internal notes contain personal contact data. Restrict database access and apply the organisation's retention/backup policy. No public message lookup endpoint is provided.

## Verification

29 backend tests passed: 9 inbox workflows, 1 rate-limit test, 14 RBAC regression tests and 5 public catalogue tests. Six frontend submission tests cover retries, CSRF, failure handling, KSA date boundaries, navigation permissions and expired anonymous-session recovery without a staff-login redirect. Existing authentication and role tests also passed. Lint and production build passed. JSX render checks verify the public forms no longer advertise local downloads. No live browser interaction was available in this tool session.
