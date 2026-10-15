# Backend structure

The backend uses packages grouped by business module, with technical layers inside each module. This keeps a module's API, business rules, persistence, and DTOs together without mixing their responsibilities. `VehicleRentalApplication` stays in `com.vehiclerental.mak`, above every module, so Spring Boot discovers components, entities, and repositories automatically.

## Layout

```text
src/main/java/com/vehiclerental/mak/
|-- VehicleRentalApplication.java
|-- config/                         # HTTP/CORS, security, scheduling configuration
|-- common/
|   |-- dto/response/               # Shared file-download and stored-file metadata
|   |-- exception/                  # API exception and global exception handler
|   `-- storage/                    # Transaction-aware upload storage
|-- auth/
|   |-- controller/
|   |-- dto/request/
|   |-- dto/response/
|   |-- entity/
|   |-- mapper/
|   |-- repository/
|   |-- security/                   # Principal, permissions, authentication filters
|   `-- service/
|-- customers/                      # Customers and their quotes/approval evidence
|-- dashboard/                      # Permission-aware reporting and insights
|-- drivers/
|-- employees/
|-- fleet/
|-- inspections/
|-- invoices/
|-- maintenance/
|-- rentals/                        # Shared individual and corporate booking domain
|-- vendors/
`-- notifications/
    |-- email/
    |   |-- controller/
    |   |-- entity/
    |   |-- repository/
    |   |-- scheduler/
    |   `-- service/
    `-- booking/
        |-- controller/
        |-- service/
        `-- sse/                    # SSE connection lifecycle and event delivery

src/main/resources/
|-- application.properties
`-- db/migration/                   # Existing versioned Flyway migrations

src/test/java/com/vehiclerental/mak/
|-- VehicleRentalApplicationTests.java
`-- <module>/integration/           # Existing API and persistence regression tests
```

The business modules use the following layers where they have corresponding classes. Empty layers are deliberately omitted.

| Package | Responsibility | Examples |
| --- | --- | --- |
| `controller` | HTTP routes, input validation, status codes, headers | `CustomerController`, `VehicleController` |
| `dto.request` | Input records and Jakarta validation constraints | `CustomerRequest`, `SignupRequest` |
| `dto.response` | API output records and file/import result types | `CustomerResponse`, `VehicleImportResult` |
| `service` | Use-case orchestration, business validation, transaction boundaries | `CustomerService`, `RentalService` |
| `repository` | Database access and queries | `CustomerRepository`, `RentalRepository` |
| `entity` | JPA entities, embeddables, composite keys | `Customer`, `RentalRecord`, `InvoiceSourceId` |
| `mapper` | Conversion between persistence models and API records | `CustomerMapper`, `DriverFileMapper` |

Authentication, customers, drivers, employees, fleet, and vendors contain entity-to-response mappers. Other modules keep their existing service-level mapping until a dedicated mapper is useful. Dashboard has no JPA entity: it reports across other modules' tables. Invoice and booking payloads currently retain their existing map-based contracts; the structural refactor does not change their JSON schema.

## Dependency rules

- Controllers delegate use cases to services. Do not add repository access or business rules to a controller.
- Services use repositories and mappers. Keep write transactions around complete use cases, not individual controller steps.
- DTOs contain transport data and validation. They do not import entities, repositories, or services.
- Repositories depend on persistence models, not HTTP controllers or DTOs.
- Entities describe persistence and must not depend on the web layer.
- Put reusable infrastructure in `common` or `config`; business-specific helpers stay in their owning module.
- Keep implementation-only helpers private. Public request/response types belong in DTO packages. Small nested values that belong exclusively to one DTO, such as quote route rows, may remain nested in that DTO.
- Use explicit imports for project types. Keep one public top-level type per Java file.
- Create service interfaces only when they provide a real abstraction; a single implementation does not need an `Impl` wrapper.

Cross-module repository access already exists in booking, invoicing, availability checks, and vendor validation. This refactor preserves that behavior. These packages organize one application; they are not independently deployable services. For future work, prefer a public service API when one module needs another module's business rules.

Some existing dashboard and notification services use `JdbcTemplate` directly, and several older endpoints use dynamic maps. Moving those queries behind dedicated read repositories and introducing typed contracts are follow-up changes that require their own API/query review. Merely renaming those services as repositories would not separate their responsibilities.

## Adding a module

1. Create the module package under `com.vehiclerental.mak`.
2. Add request/response DTOs for its HTTP contract, a controller for routes, and a service for use cases.
3. Add entities and repositories only if the module owns persisted data. Reports can use dedicated read repositories without new tables.
4. Add mapping classes when entity conversion is reused or would otherwise leak persistence details into DTOs.
5. Add new schema changes as new Flyway migrations. Do not edit migrations already applied to a database.
6. Put integration tests under the corresponding module's `integration` package. Future isolated unit tests can mirror the relevant layer, such as `service` or `mapper`.

## Running after package changes

Use Java 21 and a clean build so obsolete compiled classes from the old packages cannot be discovered as duplicate Spring beans:

```powershell
.\mvnw.cmd clean test
.\backend.cmd start
```

The Windows launcher already runs a clean build. Tests use the test configuration, H2, and mocked email delivery. The package refactor does not require database resets, new migrations, frontend changes, or API URL changes. Existing security, CORS, upload locations, schedules, and JSON field names are retained.

See [LOCAL_SETUP.md](LOCAL_SETUP.md) for local credentials and startup, [AUTH_SETUP.md](AUTH_SETUP.md) for authentication, and [EMAIL_SETUP.md](EMAIL_SETUP.md) for email configuration.
