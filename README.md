# Library Management System - Backend

A multi-library REST API for lending libraries: catalogue, members, loans, overdue fines and staff accounts, with every
library's data kept apart from every other library's.

Built with Spring Boot 3.5 on Java 21, MySQL 8, Flyway and JWT authentication.

- [Features](#features)
- [Technology](#technology)
- [Repository layout](#repository-layout)
- [Local development](#local-development)
- [Profiles](#profiles)
- [Environment variables](#environment-variables)
- [Database and Flyway](#database-and-flyway)
- [Time zone and date storage](#time-zone-and-date-storage)
- [Docker](#docker)
- [Health probes](#health-probes)
- [Authentication](#authentication)
- [Endpoints](#endpoints)
- [Provisioning the first library and administrator](#provisioning-the-first-library-and-administrator)
- [CORS](#cors)
- [Continuous integration](#continuous-integration)
- [Known limitations](#known-limitations)

## Features

- **Multi-library tenancy** - every account, book, category and loan belongs to one library, and every query is scoped
  to the caller's library.
- **Roles** - `ROLE_ADMIN` and `ROLE_LIBRARIAN` (staff), and `ROLE_MEMBER`.
- **Catalogue** - books and categories with search, filters, sorting and pagination.
- **Lending** - staff issue books to members and take them back; overdue loans are detected from their due date and
  fined per day; staff record fine payments.
- **Accounts** - administrators create members and librarians, enable, disable, lock and unlock accounts, and register
  new libraries with their first administrator; everyone can change their own password.
- **Security** - JWT access tokens with rotating refresh tokens, login rate limiting, configurable CORS, and startup
  checks that refuse unsafe production configuration.

## Technology

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 - Web, Security, Data JPA, Validation, Actuator |
| Database | MySQL 8.0 with Hibernate 6 and Flyway migrations |
| Authentication | JWT (HS256) access tokens and opaque refresh tokens |
| Build | Maven, through the included wrapper |

## Repository layout

```text
backend/
  src/main/java/com/library/lms/   config, controller, dto, entity, exception, repository, service
  src/main/resources/              application.properties, application-prod.properties, db/migration/
  src/test/java/com/library/lms/   unit and integration tests
  Dockerfile, .dockerignore, .env.example
.github/workflows/backend-ci.yml   CI: the test suite, then a Docker image smoke test
```

## Local development

### Prerequisites

- JDK 21 or newer (the build targets Java 21)
- MySQL 8.0 on `localhost:3306`

### Run

The default (development) profile still needs a database password and JWT settings - none of them has a committed
default:

```bash
cd backend
export DB_PASSWORD='<your local MySQL password>'   # DB_USERNAME defaults to root
export JWT_SECRET='<at least 32 random characters>'
export JWT_ISSUER='<a name for this deployment>'
export JWT_AUDIENCE='<a name for this API>'
./mvnw spring-boot:run                             # Windows: mvnw.cmd spring-boot:run
```

The API listens on `http://localhost:8080`. The development profile connects to `library_db` on localhost, creating it
if it is missing, lets Hibernate update the schema, and does not run Flyway. `mvnw spring-boot:run` starts the JVM in
`Asia/Kolkata` - see [Time zone and date storage](#time-zone-and-date-storage).

### Tests

```bash
cd backend
export DB_PASSWORD='<your local MySQL password>'
./mvnw -B -ntp test                                # Windows: mvnw.cmd -B -ntp test
```

- The tests need MySQL on `localhost:3306` and an account (`DB_USERNAME`, default `root`) allowed to create databases.
- The JWT settings and the `Asia/Kolkata` time zone for the test JVM come from `pom.xml`.
- Integration tests use throwaway schemas whose names start with `library_db_` - never the development database. A
  guard test fails any test that starts a Spring context without choosing one. The schemas are created on demand and
  kept between runs, except the Flyway and first-administrator tests' own, which they drop.

## Profiles

| | Development (default) | Production (`SPRING_PROFILES_ACTIVE=prod`) |
|---|---|---|
| Database | `localhost:3306/library_db`, TLS off | `DB_URL`, TLS required |
| Schema | Hibernate `ddl-auto=update`, Flyway off | Flyway migrations, Hibernate `validate` |
| SQL logging | on | off |

In every profile startup fails if `DB_PASSWORD` or a JWT setting is missing or unusable, or if the CORS origins, fine
rate or refresh-token lifetime are invalid. The production profile also refuses to start when:

- `DB_URL` is missing, turns TLS off (`useSSL=false`), does not require TLS (`sslMode=REQUIRED`, `VERIFY_CA` or
  `VERIFY_IDENTITY`), allows public key retrieval, creates the database on demand, or carries a password;
- `DB_USERNAME` is missing (`root` is allowed, with a warning);
- Hibernate is allowed to change the schema (anything but `validate` or `none`);
- the JVM's time zone differs from `APP_TIME_ZONE`, or date-times would not be stored in UTC.

## Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | production | - | `prod` selects the production profile; the Docker image sets it |
| `DB_URL` | production | - | JDBC URL of an existing database, requiring TLS |
| `DB_USERNAME` | production | `root` in development | Database account; in production a dedicated one |
| `DB_PASSWORD` | yes | - | Database password; blank is refused |
| `JWT_SECRET` | yes | - | HS256 signing key, at least 32 bytes |
| `JWT_ISSUER` | yes | - | Issuer stamped on, and required of, every access token |
| `JWT_AUDIENCE` | yes | - | Audience stamped on, and required of, every access token |
| `APP_TIME_ZONE` | no | `Asia/Kolkata` | Business time zone; the JVM must run in it |
| `CORS_ALLOWED_ORIGINS` | no | empty - none | Comma-separated exact browser origins |
| `FINE_DAILY_RATE` | no | `1.00` | Fine per overdue day; zero or more, at most two decimals |
| `JWT_ACCESS_TOKEN_VALIDITY` | no | `PT1H` | Access-token lifetime, as an ISO-8601 duration |
| `JWT_REFRESH_TOKEN_VALIDITY` | no | `P7D` | Login session lifetime, as an ISO-8601 duration |
| `JWT_REFRESH_TOKEN_RETENTION` | no | `P30D` | How long ended sessions are kept; at least the session lifetime |
| `JWT_REFRESH_TOKEN_CLEANUP_INTERVAL` | no | `PT1H` | How often ended sessions are swept away |
| `BOOTSTRAP_ADMIN_LIBRARY` | first start | - | Name of the first library, created when there are no accounts |
| `BOOTSTRAP_ADMIN_USERNAME` | first start | - | Username of its first administrator |
| `BOOTSTRAP_ADMIN_EMAIL` | first start | - | Email of its first administrator |
| `BOOTSTRAP_ADMIN_PASSWORD` | first start | - | Password of its first administrator; 8 to 72 characters |

`backend/.env.example` lists them all with placeholders. Copy it to `backend/.env`, which git ignores, and fill it in;
never commit real values.

Access tokens last one hour by default, set by `JWT_ACCESS_TOKEN_VALIDITY` above. Five consecutive failed logins block
a username for fifteen minutes; that limit has no dedicated environment variable, but the `security.login.*` properties
behind it can still be overridden through Spring's environment-variable binding - for example
`SECURITY_LOGIN_MAX_FAILED_ATTEMPTS`.

## Database and Flyway

In production Flyway applies the migrations in `backend/src/main/resources/db/migration` at startup, before Hibernate
starts; Hibernate then only validates the schema.

| Migration | Adds |
|---|---|
| `V1__initial_schema.sql` | `libraries`, `users`, `categories`, `books`, `transactions` |
| `V2__fine_payment_tracking.sql` | fine payment status, time and recording staff member on `transactions` |
| `V3__refresh_tokens.sql` | `refresh_tokens`, which holds token hashes, never tokens |

- The database must already exist, and the account needs rights to create and alter tables in it.
- An applied migration is never edited: Flyway checksums it and refuses to start if it changed. A schema change is a new
  migration file.
- Flyway `clean` is disabled and `baseline-on-migrate` is off.
- The migrations create structure only. No library, account or other data is seeded.

**Existing databases.** A database whose schema Hibernate built with `ddl-auto=update` - such as a local development
`library_db` - has tables but no Flyway history, so the production profile refuses to start against it. Its schema can
also differ from V1-V3: Hibernate lists ENUM values in a different order from V1, and tables that existed before the
entities may carry other constraint names. Deploy to a fresh, empty database. This project provides no procedure for
baselining an existing one; do not baseline one without first verifying that its schema matches V1-V3 exactly.

## Time zone and date storage

- **Business day.** Issue dates, due-date checks, overdue status and fines follow the JVM's date, so the JVM must run in
  `APP_TIME_ZONE` (default `Asia/Kolkata`) by starting it with `-Duser.timezone=Asia/Kolkata`. `pom.xml` does this for
  the tests and for `mvnw spring-boot:run`, and the Docker image does it in its entry point; a plain `java -jar` has to
  pass it too. The production profile refuses to start if the two zones differ.
- **UTC storage.** `DATETIME` columns - `created_at`, `fine_paid_at` and the refresh-token times - hold UTC:
  `application.properties` sets the MySQL driver's `connectionTimeZone=UTC`, and existing rows were written that way.
  `DATE` columns - issue, due and return dates - hold the calendar date, unconverted. The production profile rejects a
  `DB_URL` that sets `connectionTimeZone` or `serverTimezone` to anything but UTC, or sets `preserveInstants=false`.
- **API values.** Date-times in responses, such as `createdAt`, `finePaidAt` and error `timestamp` values, are local
  time in the business zone, without an offset.
- **Manual SQL.** A MySQL server running in another zone returns `NOW()` in that zone, not in the UTC the columns hold.

## Docker

```bash
docker build -t library-management-backend ./backend
cp backend/.env.example backend/.env                # then fill in backend/.env
docker run --rm -p 8080:8080 --env-file backend/.env library-management-backend
```

- The build stage compiles with the Temurin 21 JDK. It does not run the tests, which need MySQL; CI runs them first.
- The runtime image is the Temurin 21 JRE and the application jar, run as a non-root user with
  `SPRING_PROFILES_ACTIVE=prod` and the JVM pinned to `Asia/Kolkata`.
- No secret is baked into the image. Database and JWT settings are supplied when the container starts, and the database
  must be reachable from the container and meet the production rules above.
- Setting `APP_TIME_ZONE` to another zone at run time makes startup fail on purpose, because the image pins the JVM to
  `Asia/Kolkata`; another business zone needs an image built with a matching `-Duser.timezone`.
- The image defines no `HEALTHCHECK`. Point your platform's probes at the endpoints below.

## Health probes

| Endpoint | Meaning | Includes the database |
|---|---|---|
| `GET /actuator/health/liveness` | the process is alive - restart it if not | no |
| `GET /actuator/health/readiness` | it can serve requests - send it traffic | yes |
| `GET /actuator/health` | overall status | yes |
| `GET /actuator/info` | deliberately empty | - |

The probes need no token and return a status only: `UP`, or `DOWN` or `OUT_OF_SERVICE` with HTTP 503, and never
components or details. No other Actuator endpoint is exposed.

## Authentication

1. **Log in** - `POST /api/auth/login` with `{"username", "password"}` returns `{"token", "refreshToken"}`.
2. **Call the API** - send `Authorization: Bearer <token>`. The access token is a JWT valid for one hour by
   default, or for whatever `JWT_ACCESS_TOKEN_VALIDITY` says. The account's role, library and status are read from the
   database on every request, so a disabled or locked account is refused even with a token that has not expired.
3. **Refresh** - `POST /api/auth/refresh` with `{"refreshToken"}` returns a new pair. Each refresh token works once,
   and presenting one that was already used ends the whole session. A session ends `JWT_REFRESH_TOKEN_VALIDITY` after
   login, however often it is refreshed.
4. **Log out** - `POST /api/auth/logout` with `{"refreshToken"}` ends the session. It answers 204 whether the token is
   valid, invalid, unknown or already revoked; malformed input - a missing, blank or over-length `refreshToken` - fails
   validation with 400. The access token already issued stays valid until it expires - at most
   `JWT_ACCESS_TOKEN_VALIDITY` after it was issued, an hour by default - so discard it.
5. **Change password** - `POST /api/auth/password`, signed in, with `{"currentPassword", "newPassword"}` answers 204
   and ends every refresh session of the account.

- A failed login answers 401 `Invalid username or password` whatever the reason; five consecutive failures block the
  username for fifteen minutes.
- A refused refresh answers 401 `Invalid or expired refresh token.` whatever the reason.
- The server stores only a SHA-256 hash of each refresh token. A session's rows are kept for
  `JWT_REFRESH_TOKEN_RETENTION` after it ends - that is what keeps reuse of an old token recognisable -
  and a sweep every `JWT_REFRESH_TOKEN_CLEANUP_INTERVAL` removes the ones past it. A session that is
  still running is never touched.
- Login, refresh, logout and the health probes are the only endpoints open without a token.

## Endpoints

All data is confined to the caller's library. Lists accept `page` (from 0), `size` (default 10, at most 50), `sortBy`
and `direction`.

| Method and path | Access |
|---|---|
| `POST /api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` | public |
| `POST /api/auth/password` | any account |
| `GET /api/books`, `/api/books/{id}`, `/api/books/search`, `/api/books/category/{category}` | any account |
| `POST /api/books`; `PUT` and `DELETE /api/books/{id}` | admin, librarian |
| `GET /api/categories` | any account |
| `POST /api/categories`; `PUT` and `DELETE /api/categories/{id}` | admin, librarian |
| `POST /api/transactions/issue` with `{"bookId", "memberId", "dueDate"}` | admin, librarian |
| `POST /api/transactions/{id}/return` | admin, librarian |
| `POST /api/transactions/{id}/fine-payment` - records a payment taken by staff | admin, librarian |
| `GET /api/transactions/{id}`, `/api/transactions/user/{userId}` | staff; members see only their own |
| `GET /api/transactions/book/{bookId}`, `/api/transactions/status/{status}` | admin, librarian |
| `POST /api/users` - creates a member or a librarian | admin |
| `PATCH /api/users/{userId}/status` - enables, disables, locks or unlocks an account | admin |
| `POST /api/libraries` with `{"name", "admin": {"username", "email", "password"}}` | admin |

An administrator cannot disable or lock their own account.

## Provisioning the first library and administrator

Every endpoint that creates an account needs an administrator who is already signed in, and the migrations seed no
account. The first library and administrator are therefore created at startup, from the environment:

1. Set `BOOTSTRAP_ADMIN_LIBRARY`, `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD`.
2. Start the application against the empty database. It finds no accounts, creates the library and its administrator
   in one transaction, and logs the new ids and username.
3. Sign in as that administrator. From then on every library and account is created through the API: `POST
   /api/libraries` registers a library with its first administrator, and `POST /api/users` adds members and
   librarians.

How the bootstrap behaves:

- **Only into an empty database.** If a single account exists it does nothing and does not read the variables, so they
  can stay blank - or stay set - on every later start. Configuration cannot add an administrator to a database that
  already has one.
- **All four or none.** With no accounts, a missing or invalid value stops startup with a message naming the variable
  and the rule it broke, never its value. The rules are the API's: username 3 to 255 characters, a valid email,
  password 8 to 72 characters, library name at most 100.
- **Always an administrator of the new library.** There is no setting for a role or a library id.
- **Stored like any other account.** The password goes through the application's BCrypt encoder; neither it, its hash
  nor the email is ever logged.
- **Several instances starting together** are safe: the unique username index lets one create the account, and the
  others find the table no longer empty and carry on.

## CORS

`CORS_ALLOWED_ORIGINS` lists the browser origins allowed to call the API from another site, as exact
`scheme://host[:port]` values separated by commas. Empty, the default, allows none. A wildcard, a path, a trailing
slash or credentials in an entry stop the application at startup.

- Allowed methods: `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`.
- Allowed request headers: `Authorization`, `Content-Type`, `Accept`.
- Credentialed requests (cookies) are not allowed; the token travels in the `Authorization` header.
- Preflight responses may be cached for an hour.
- A request from an origin that is not listed is refused with 403 before it reaches the API.
- Behind a reverse proxy, list the origin the browser sees.

## Continuous integration

`.github/workflows/backend-ci.yml` runs on pushes to `main` and on pull requests - both only when they touch `backend/`
or the workflow file, so a push that changes only this README does not trigger it - and on demand:

1. **Tests** - the full suite on Java 21 against a MySQL 8.0 service container.
2. **Docker smoke test** - once the tests pass, builds the image, starts it with the production profile against a
   fresh MySQL 8.0 over TLS, and checks that readiness and liveness answer 200 and that `GET /api/books` without a token
   answers 401.

The credentials in the workflow are test-only values for throwaway containers. The workflow runs once the repository is
on GitHub.

## Known limitations

- **Single instance.** Login rate limiting is kept in memory, per instance and per username; several instances
  multiply the limit.
- **Access tokens** last one hour by default, configurable through `JWT_ACCESS_TOKEN_VALIDITY`, and are not
  revoked before they expire, even by logout or a password change.
- **Refresh-token records** outlive their session by `JWT_REFRESH_TOKEN_RETENTION`, so that reuse of an old
  token is still recognised, and are then swept away. Every instance runs the sweep.
- **Fine payments** are recorded by staff; there is no payment gateway.
- **Libraries** can be registered by any administrator.
- **Dates and times** in API responses carry no offset, and one business time zone applies to every library.
- **Test schemas** whose names start with `library_db_` accumulate on the machine that runs the tests.
