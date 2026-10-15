# Enable email delivery

For persistent Windows development settings, run `.\backend.cmd setup`, choose `Y` for SMTP, then start using `.\backend.cmd start`. Passwords are encrypted under your Windows account outside the repository. See [LOCAL_SETUP.md](LOCAL_SETUP.md). The environment-variable instructions below remain available for other environments.

The application uses configurable SMTP through Spring Boot's mail support:
https://docs.spring.io/spring-boot/reference/io/email.html

Get the SMTP host, port, authentication method and permitted sender address from your email provider. Use a dedicated sender account. If the provider requires OAuth rather than SMTP password/app-password authentication, this configuration needs a provider-specific adapter.

Set these variables in the same PowerShell window where you start the backend:

```powershell
$env:MAIL_HOST = "your-provider-smtp-host"
$env:MAIL_PORT = "587"
$env:MAIL_USERNAME = "your-sender@example.com"
$env:MAIL_FROM = "your-sender@example.com"
$mailCredential = Read-Host "SMTP password or app password" -AsSecureString
$env:MAIL_PASSWORD = [System.Net.NetworkCredential]::new("", $mailCredential).Password
$env:MAIL_AUTH = "true"
$env:MAIL_STARTTLS = "true"
$env:MAIL_SSL = "false"
$env:MAIL_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

Use your existing DB environment variables as before. For implicit TLS on port 465, use `MAIL_SSL=true` and `MAIL_STARTTLS=false` if your provider specifies that configuration. For a local SMTP testing tool without authentication/TLS, set `MAIL_AUTH=false`, `MAIL_STARTTLS=false`, and its actual port. Keep `MAIL_ENABLED=false` until ready to send.

No credentials are stored in the frontend or committed configuration. These variables last for the current PowerShell session. A deployed server should use its environment/secret manager.

## Workflow

- Individual and corporate bookings queue confirmation/update emails when their notification checkbox is enabled. Assignment updates include the selected vehicle and driver. Advance bookings remain unassigned until trip day.
- Issuing an invoice automatically queues one email to its billing email, with a PDF snapshot attached. Creating or editing a draft does not email it.
- Invoice details provide Email Invoice / Resend Invoice. An already queued or failed message is reused rather than duplicated; use Retry Email for failures.
- Detail pages show delivery history. Accepted by mail server means SMTP acceptance; inbox delivery and bounces require a provider webhook integration.
- Delivery polls every 15 seconds. Failures retry up to five attempts with increasing delays and can then be retried manually. Queued messages survive backend restarts.
- Enabling SMTP sends the queued backlog. Use test customer addresses while configuring it. Tests use an isolated H2 database and a mocked sender.

## PDF downloads

Download in the invoice list or details returns a `.pdf` from the backend. Drafts show DRAFT INVOICE. The document includes metadata, structured seller/buyer details, linked rentals, all line items, VAT by rate, totals and balance, notes, contact person and bank details. Long tables wrap across pages with repeated column headers.

PDF generation uses Apache PDFBox: https://pdfbox.apache.org/3.0/migration.html
This is the printable invoice document; it does not submit tax documents to a regulatory service.
