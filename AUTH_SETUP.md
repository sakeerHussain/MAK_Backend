# Authentication setup

Email/password authentication is enabled for all application APIs. There is no preview bypass, built-in password or automatically privileged public registration. Google and Microsoft are intentionally not connected yet.

## Create the first administrator

In a PowerShell terminal in the backend directory, generate a setup code and start the backend in that same terminal:

```powershell
$env:AUTH_BOOTSTRAP_CODE = [guid]::NewGuid().ToString('N')
Write-Output $env:AUTH_BOOTSTRAP_CODE
.\mvnw.cmd spring-boot:run
```

Copy the displayed setup code into **Administrator setup code** on `#/sign-up`. Enter your name, email and a password with at least 12 characters, including letters, a number and a symbol. Confirm the password and form checkbox, then create the account. Sign in with that email and password.

The setup code is accepted only until the first administrator has been created. Flyway V10 creates the authentication tables on startup. No accounts are seeded. You can remove the setup-code environment variable after setup; retain your actual account password securely.

## Register and approve staff

New signups after initial setup receive **Pending / Viewer** and cannot log in. An administrator opens **Staff & Users**, chooses **Review Access**, assigns a role, changes status to **Active**, and saves. Role/status changes invalidate every existing session for that account. Deactivate accounts to retain their security history. The last active administrator cannot be demoted or deactivated.

| Role | Default access |
| --- | --- |
| Admin | All implemented modules, account administration, security audit and settings navigation |
| Manager | Operational and invoice read/write; employee read; no account administration or security audit |
| Operations | Fleet, drivers, vendors, customers, bookings, corporate trips, maintenance and inspections read/write |
| Finance | Invoice read/write; fleet, drivers, customers and individual/corporate booking read |
| Viewer | Fleet, drivers, customers, individual/corporate bookings, maintenance and inspections read only |

All roles can open Dashboard. Sidebar links and read-only controls follow the role; APIs independently enforce permissions, including document/image/PDF endpoints. Unimplemented sidebar modules remain inaccessible until their permissions and backend are added. Roles are fixed defaults; individual permission overrides are not part of this version.

## Sessions and recovery

Session cookies are HttpOnly and SameSite=Lax. Normal sessions expire after 30 minutes idle. Keep me signed in uses a persistent cookie and a 30-day idle session. Sessions currently live in the running backend process; restarting it requires signing in again. Use a shared Spring Session store before running multiple backend instances.

Every mutation requires a session CSRF token. The frontend includes cookies in API requests, obtains the token in memory, and refreshes it after login/logout. Passwords are stored with BCrypt cost 12, and passwords longer than 72 UTF-8 bytes are rejected. Five wrong passwords lock an account for 15 minutes. Authentication endpoints additionally limit requests per remote IP in each server process.

Forgot Password sends a generic response to avoid exposing registered accounts. When SMTP is enabled through the existing email configuration, an active account receives a single-use reset link valid for 30 minutes. Only the token hash is stored in the reset table; the queued email necessarily contains its link until delivery. Resetting a password revokes all sessions. Recovery emails cannot be read or retried through booking/invoice email APIs. No SMTP message is sent by tests.

Set `FRONTEND_URL` to the actual frontend origin for reset links. With the development proxy this is usually `http://localhost:5173`. Enable `SESSION_COOKIE_SECURE=true` when serving the application over HTTPS. Use a same-origin `/api` reverse proxy for production, and restrict CORS to your frontend origin. Do not disable CSRF for deployment.

## Endpoints

- GET `/api/auth/config`, `/api/auth/csrf`, `/api/auth/me`
- POST `/api/auth/signup`, `/api/auth/login`, `/api/auth/logout`
- POST `/api/auth/forgot-password`, `/api/auth/reset-password`
- GET `/api/users`, `/api/users/roles` (Admin)
- PATCH `/api/users/{id}/access` (Admin)
- GET `/api/security-audit` (Admin, latest 200 account security events)

Account sessions and identities are separate from Driver/Employee HR records. This implementation does not automatically link an employee record to a login account.

Run backend `mvn test` and frontend `npm test` for regression coverage. Authentication tests apply the actual Spring Security filter chain and use an isolated H2 database with a mocked mail sender. Browser smoke tests use a separate in-memory database, never production PostgreSQL.

If your frontend uses a port other than 5173, configure app.cors.allowed-origins with that exact origin (for example http://localhost:5180). Keep the frontend API proxy pointed at the backend port.
