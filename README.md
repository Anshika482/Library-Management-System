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
- [Paying a fine by card](#paying-a-fine-by-card)
- [Assistant](#assistant)
- [Audit log](#audit-log)
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
- **Audit log** - every change to accounts, passwords, libraries, loans and fines is recorded in its library's audit
  log, refusals included, and read back by an administrator through `GET /api/audit-events`.

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

Because development uses `ddl-auto=update`, Hibernate does not reliably widen an existing MySQL `ENUM`. After pulling a
migration that widens `audit_events.action` or `target_type`, an existing `library_db` may need Flyway to apply the
migration, or the `audit_events` table to be recreated locally.

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
rate, refresh-token lifetime or password-reset token lifetime are invalid. The production profile also refuses to
start when:

- `DB_URL` is missing, turns TLS off (`useSSL=false`), does not require TLS (`sslMode=REQUIRED`, `VERIFY_CA` or
  `VERIFY_IDENTITY`), allows public key retrieval, creates the database on demand, or carries a password;
- `DB_USERNAME` is missing (`root` is allowed, with a warning);
- Hibernate is allowed to change the schema (anything but `validate` or `none`);
- the JVM's time zone differs from `APP_TIME_ZONE`, or date-times would not be stored in UTC;
- `PAYMENT_GATEWAY_PROVIDER` is not `razorpay`, or its key id or secret is missing. The sandbox gateway marks fines
  paid while no money moves, and nothing inside the application would notice, so production refuses to start on it.
- `CHAT_PROVIDER` is not `anthropic`, or `ANTHROPIC_API_KEY` is missing. The scripted assistant answers from a
  keyword list, and a deployment left on it has an assistant in name only - every request succeeds, so nothing
  inside the application reports it.

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
| `PASSWORD_RESET_TOKEN_VALIDITY` | no | `PT30M` | How long a self-service reset token works; at most `PT24H` |
| `PASSWORD_RESET_TOKEN_RETENTION` | no | `P1D` | How long spent reset tokens are kept before they are swept |
| `PASSWORD_RESET_TOKEN_CLEANUP_INTERVAL` | no | `PT1H` | How often spent reset tokens are swept away |
| `MAIL_HOST` | production | empty - nothing sent | SMTP server that delivers password reset links |
| `MAIL_PORT` | no | `587` | SMTP submission port |
| `MAIL_USERNAME` | no | - | SMTP account; also the fallback From address |
| `MAIL_PASSWORD` | no | - | That account's password |
| `MAIL_FROM` | production | `MAIL_USERNAME` | Address reset messages come from |
| `MAIL_STARTTLS` | no | `true` | Upgrade the SMTP connection to TLS |
| `APP_RESET_LINK_BASE_URL` | production | empty - nothing sent | Page the reset link points to; the token is added to it |
| `PAYMENT_GATEWAY_KEY_ID` | no | empty - no online payment | Razorpay key id; public, sent to the browser |
| `PAYMENT_GATEWAY_KEY_SECRET` | no | empty - no online payment | Razorpay key secret; opens orders and verifies signatures, never published |
| `PAYMENT_GATEWAY_PROVIDER` | no | `hmac-sandbox` | `razorpay`, or `hmac-sandbox` for local development; anything else stops startup |
| `PAYMENT_GATEWAY_BASE_URL` | no | `https://api.razorpay.com` | Razorpay's API host; overridden only to point at a stub |
| `PAYMENT_GATEWAY_CONNECT_TIMEOUT` | no | `PT3S` | How long to wait for a connection to the provider; zero or less is refused |
| `PAYMENT_GATEWAY_READ_TIMEOUT` | no | `PT8S` | How long to wait for its answer; zero or less is refused |
| `PAYMENT_CURRENCY` | no | `INR` | Currency fines are charged in, ISO 4217 |
| `CHAT_PROVIDER` | production | `scripted` | `anthropic` for Claude, or `scripted`; anything else stops startup |
| `ANTHROPIC_API_KEY` | production | - | Provider key; read once into the SDK client, never logged or returned |
| `CHAT_MODEL` | no | `claude-opus-5` | Which Claude model answers |
| `CHAT_CONNECT_TIMEOUT` | no | `PT5S` | How long to wait for a connection to the provider |
| `CHAT_READ_TIMEOUT` | no | `PT30S` | How long to wait for its answer |
| `CHAT_REQUEST_TIMEOUT` | no | `PT45S` | How long the whole call may take, retries included |
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
| `V4__password_reset_tokens.sql` | `password_reset_tokens`, which holds reset-token hashes, never tokens |
| `V5__audit_events.sql` | `audit_events`, the audit log: ids, action names and times only |
| `V6__audit_loan_actions.sql` | Widens the audit action and target enums to cover loans and fines |
| `V7__payments.sql` | `payments`, one row per online fine payment attempt: references, amount and status |

- The database must already exist, and the account needs rights to create and alter tables in it.
- An applied migration is never edited: Flyway checksums it and refuses to start if it changed. A schema change is a new
  migration file.
- Flyway `clean` is disabled and `baseline-on-migrate` is off.
- The migrations create structure only. No library, account or other data is seeded.

**Existing databases.** A database whose schema Hibernate built with `ddl-auto=update` - such as a local development
`library_db` - has tables but no Flyway history, so the production profile refuses to start against it. Its schema can
also differ from V1-V4: Hibernate lists ENUM values in a different order from V1, and tables that existed before the
entities may carry other constraint names. Deploy to a fresh, empty database. This project provides no procedure for
baselining an existing one; do not baseline one without first verifying that its schema matches V1-V4 exactly.

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
6. **Forgot password** - `POST /api/auth/forgot-password` with `{"email"}` always answers 202 with the same message,
   whether or not an account has that address. For an enabled, unlocked account it issues a reset token: 256 random
   bits, stored only as a SHA-256, valid for `PASSWORD_RESET_TOKEN_VALIDITY`, and spending any earlier one. Each address
   may ask three times in fifteen minutes; further requests are dropped, with the same 202. The account is looked up
   and the token issued after the answer has been sent, on a small background queue, so the answer takes the same
   time whether or not the address has an account. The link is then emailed to the address - see
   [Delivering reset links](#delivering-reset-links).
7. **Reset password** - `POST /api/auth/reset-password` with `{"token", "newPassword"}` answers 204 and works once:
   the token is spent, the password is set, every refresh session ends and the login block is cleared. Any token that
   cannot be used - unknown, used, superseded, expired, or of an account since disabled - gets the same 400. A new
   password outside 8 to 72 characters is refused without using the token up.

- A failed login answers 401 `Invalid username or password` whatever the reason; five consecutive failures block the
  username for fifteen minutes.
- A refused refresh answers 401 `Invalid or expired refresh token.` whatever the reason.
- The server stores only a SHA-256 hash of each refresh token. A session's rows are kept for
  `JWT_REFRESH_TOKEN_RETENTION` after it ends - that is what keeps reuse of an old token recognisable -
  and a sweep every `JWT_REFRESH_TOKEN_CLEANUP_INTERVAL` removes the ones past it. A session that is
  still running is never touched.
- Login, refresh, logout, forgot-password, reset-password and the health probes are the only endpoints open without
  a token.

### Delivering reset links

The reset link is emailed over SMTP, from `MAIL_FROM` (or `MAIL_USERNAME`) through `MAIL_HOST`, after the token is
committed. The link is `APP_RESET_LINK_BASE_URL` with the token added as a `token` query parameter, so point it at
the page that asks for a new password and posts it to `POST /api/auth/reset-password`.

- **Unconfigured means nothing is sent.** With `MAIL_HOST` blank, the request is logged as not delivered - by account
  id - and no connection is attempted. That is the development and CI default.
- **Production refuses to start unconfigured.** Under the `prod` profile a blank `MAIL_HOST`, a blank sender address
  or a blank `APP_RESET_LINK_BASE_URL` stops startup, rather than issuing resets that nobody receives.
- **A failure to send changes nothing else.** The caller still gets 202, the stored token stays usable until it
  expires, and the account holder can ask again. Only the failure's type is logged: an SMTP rejection quotes the
  address it rejected.
- **Nothing sensitive is logged.** Not the token, the link, the address or the message - the log lines name an
  account id, as the rest of the reset flow does.
- **STARTTLS is on by default.** The link is a credential until it is used or expires, and it travels over this
  connection along with the SMTP password.

## Endpoints

All data is confined to the caller's library. Lists accept `page` (from 0), `size` (default 10, at most 50), `sortBy`
and `direction`.

| Method and path | Access |
|---|---|
| `POST /api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` | public |
| `POST /api/auth/forgot-password`, `/api/auth/reset-password` | public |
| `POST /api/auth/password` | any account |
| `GET /api/books`, `/api/books/{id}`, `/api/books/search`, `/api/books/category/{category}` | any account |
| `POST /api/books`; `PUT` and `DELETE /api/books/{id}` | admin, librarian |
| `GET /api/categories` | any account |
| `POST /api/categories`; `PUT` and `DELETE /api/categories/{id}` | admin, librarian |
| `POST /api/transactions/issue` with `{"bookId", "memberId", "dueDate"}` | admin, librarian |
| `POST /api/transactions/{id}/return` | admin, librarian |
| `POST /api/transactions/{id}/fine-payment` - records a payment taken by staff | admin, librarian |
| `POST /api/transactions/{id}/payment-order` - opens a card payment for a fine | the loan's member, or staff |
| `POST /api/transactions/{id}/payment-verification` - settles it once verified | the loan's member, or staff |
| `GET /api/transactions/{id}`, `/api/transactions/user/{userId}` | staff; members see only their own |
| `GET /api/transactions/book/{bookId}`, `/api/transactions/status/{status}` | admin, librarian |
| `GET /api/users/me` - your own account, including the id other calls need | any account |
| `GET /api/users` - lists accounts | admin: every account; librarian: members only |
| `GET /api/users/{userId}` - one account | admin: any account; librarian: members only |
| `POST /api/users` - creates a member or a librarian | admin |
| `PATCH /api/users/{userId}/status` - enables, disables, locks or unlocks an account | admin |
| `POST /api/users/{userId}/password-reset` with `{"newPassword"}` | admin: any account; librarian: members only |
| `POST /api/libraries` with `{"name", "admin": {"username", "email", "password"}}` | admin |
| `GET /api/audit-events` - the library's audit log | admin |
| `POST /api/chat` with `{"message"}` - asks the assistant a question | any account |

An administrator cannot disable or lock their own account.

`GET /api/users` filters by `keyword` (username or email, case-insensitive), `role`, `enabled` and
`accountNonLocked`, and sorts by `id`, `username`, `email` or `role`. The user directory never returns a password or
its hash. A librarian who filters for administrators or librarians gets 403, and a staff account or another library's
account is answered with 404, exactly like an id that does not exist. Members have no directory access.

**Forgotten passwords** are reset by staff: `POST /api/users/{userId}/password-reset` sets a new password, 8 to 72
characters like any other, and answers 204. It ends every refresh session of the account and clears its failed-login
block, so the owner can sign in straight away. An administrator may reset any account of their library except their
own (400 - use `POST /api/auth/password`, which asks for the current password); a librarian may reset members only
(403 for staff); members may reset nobody's. Another library's account is a 404, and neither the password nor its hash
is ever returned or logged.

## Paying a fine by card

A fine can be settled at the desk, unchanged, with `POST /api/transactions/{id}/fine-payment` - staff only. It can
also be paid by card, in two steps, by the member who owes it or by staff on their behalf:

1. **Open an order** - `POST /api/transactions/{id}/payment-order` answers with the provider's order reference, the
   amount, the currency and the public merchant key. Asking again while an order is still open returns that same
   order rather than a second one. The fine must be outstanding: a book still out, a fine already paid or nothing
   owed is refused with the same 409 the desk endpoint gives.
2. **Verify what came back** - `POST /api/transactions/{id}/payment-verification` with
   `{"providerOrderId", "providerPaymentId", "signature"}`. The signature is recomputed on the server with
   `PAYMENT_GATEWAY_KEY_SECRET` and must be the provider's over exactly those two references. Only then is the fine
   marked `PAID`, and only then is a `FINE_PAID` audit event recorded.

- **Nothing is believed without verification.** A client claiming a payment succeeded changes nothing. A signature
  that does not match is a 400 that says only that the payment could not be verified - never which part was wrong -
  and the attempt is stored as a failed payment.
- **Paying twice is refused in three places**: the fine's own state, a loan that already has a succeeded payment, and
  unique provider references in the database. Sending the same verified payment again returns the same answer and
  changes nothing - no second settlement, no second audit event.
- **Library-scoped and owned.** A loan of another library is not found; a loan of another member is refused to
  members and allowed to that library's staff.
- **No card data anywhere.** The card is entered on the provider's pages. This application receives, logs and stores
  only the provider's two references, an amount and a status - there is no column, field or log line for a number,
  expiry, CVV or holder name.
- **Production runs the real provider or does not start.** The sandbox is the default because development and CI
  have no merchant account; under the `prod` profile a missing, blank or non-`razorpay` provider, or a missing key
  id or secret, stops startup.
- **The provider is given a short, configurable window.** `PAYMENT_GATEWAY_CONNECT_TIMEOUT` (default `PT3S`) and
  `PAYMENT_GATEWAY_READ_TIMEOUT` (default `PT8S`) bound every call; a zero or negative value is refused at startup.
  The call happens inside the transaction that writes the payment row, so a provider that goes quiet would otherwise
  hold a database connection for as long as it liked.
- **Two providers, one flow.** `PAYMENT_GATEWAY_PROVIDER=razorpay` opens each order server-side on Razorpay's
  Orders API (`POST /v1/orders`, merchant credentials in an `Authorization` header, amount in paise) and verifies
  the `razorpay_signature` it returns. `hmac-sandbox`, the default, opens the order in process and makes exactly the
  same signature check, so development and the tests exercise the real flow without a merchant account. A provider
  name nobody implements stops startup rather than quietly falling back to the sandbox.
- **Unconfigured means unavailable.** With `PAYMENT_GATEWAY_KEY_ID` or `PAYMENT_GATEWAY_KEY_SECRET` blank, an order
  is refused with 503 and fines are taken at the desk instead. A provider that cannot be reached, refuses the order
  or answers without one is also a 503, and the response repeats nothing the provider said. Both providers sit
  behind the `PaymentGateway` interface, so adding a third is one implementation and a change of credentials.

## Assistant

`POST /api/chat` with `{"message": "How do I pay a fine?"}` answers one question for any signed-in account, and
returns `{"reply", "assistant", "answeredAt"}`.

- **Two assistants, one endpoint.** `CHAT_PROVIDER=anthropic` answers with Claude through the official SDK;
  `scripted`, the default, matches keywords against a fixed script, calls no provider and needs no key. Development
  and the test suite run on the script, so neither needs a credential and no request leaves the machine. An unknown
  provider name stops startup, and naming `anthropic` without a key stops it too - an assistant that 503s every
  question is worse than a deployment that will not start.
- **Production runs the real assistant or does not start.** Under the `prod` profile a missing, blank or
  non-`anthropic` provider, or a missing key, stops startup - the script is a development default and must not
  reach production, where "I cannot answer that yet" to every question looks exactly like a working assistant.
- **What is sent to the provider.** A system prompt naming the library and whether the caller is staff or a member,
  and the question itself. No username, email, account id, token, password or hash, and nothing about any other
  member. The prompt also forbids inventing fines, due dates, opening hours or anything about another member - the
  model is given no records and is told to send people to staff instead.
- **The provider is called outside the database transaction.** The caller's context is resolved in a short read-only
  transaction that commits before the network call begins, so a slow provider cannot hold a database connection.
- **A provider that fails is a 503.** Timeouts, a refused key, rate limits, server errors and answers with no text
  all become the same "The assistant is unavailable right now." Nothing the provider said reaches the response or
  the log - only the failure's type is recorded.
- **Scoped to the caller's library.** The library, account and role an assistant is given come from the
  authenticated account, never from the request, so a question naming another library is still answered for the
  caller's own. A request body carries nothing but the message.
- **Nothing sensitive goes in or out.** An answer never repeats the question back and carries no password, hash,
  token, role or account detail. The question itself is not logged - the log records that an account asked
  something, by id.
- **Bounded.** A missing, blank or over-1000-character message is refused with 400; an anonymous caller gets 401
  without the assistant being reached.

## Audit log

Changes to accounts, passwords, libraries, loans and fines are recorded in `audit_events`: who did it (their account
id), what (an action name), to which record (a user, a library or a loan, by id), in which library, when, and whether
it went through.

| Action | Recorded when |
|---|---|
| `USER_CREATED` | an administrator creates an account; refused when asking for a role that cannot be assigned |
| `USER_STATUS_CHANGED` | an account is enabled, disabled, locked or unlocked; refused on self-lockout |
| `PASSWORD_CHANGED` | an account holder changes their own password; refused on a wrong current password |
| `PASSWORD_RESET_BY_STAFF` | staff reset someone's password; refused for staff targets and self-reset |
| `PASSWORD_RESET_REQUESTED` | a self-service reset token is issued; refused for a disabled or locked account |
| `PASSWORD_RESET_COMPLETED` | a reset token is redeemed; refused when used, expired or its account disabled |
| `LIBRARY_CREATED` | an administrator registers a library - recorded in the creator's library |
| `LIBRARY_BOOTSTRAPPED` | the first library is created at startup - recorded in that library |
| `BOOK_ISSUED` | staff issue a book to a member; refused for an unknown book or member, an ineligible member, or no copy left |
| `BOOK_RETURNED` | staff take a book back; refused for a loan that is not open, or a copy the library does not own |
| `FINE_PAID` | staff record that a fine was paid; refused while the book is out, when it is already paid, and when nothing is owed |

- **Nothing secret can be stored.** Every column is an id, an action name or a time; there is no free text, so no
  password, hash, token or address can reach the audit log.
- **Library-scoped.** Every event belongs to the library the change happened in and is read through that library only.
  The repository can append events and read one library's events; it cannot change, delete or list them all.
- **In step with the change.** A successful change and its event commit together or not at all. A refusal is recorded
  in a transaction of its own, so the refusal's rollback does not erase it.
- **Actor.** The signed-in account that made the change - for a loan, the member of staff who issued, returned or
  took payment, never the borrower. The borrower is on the loan the event points at. Self-service resets and the
  startup bootstrap have no actor.
- **A `LOAN` target id is the transaction id**, the same id `GET /api/transactions/{id}` takes, so
  `?targetType=LOAN&targetId=42` reads everything that happened to loan 42. A refusal to return or pay carries the
  id the caller named, whether or not a loan with it exists in their library; a refusal to issue carries no target,
  because no loan was created. An event never records the fine amount, the member's name or the book's title - the
  loan row holds those.
- **Not recorded:** reads, and attempts that name no existing account (an unknown email or reset token), which have no
  library to belong to. A member stopped by the security rules never reaches the code that records.

### Reading the log

`GET /api/audit-events` returns one page of the caller's own library's events, newest first. Administrators only:
librarians and members get 403, and a caller with no token gets 401.

Each event carries `id`, `action`, `outcome`, `actorUserId`, `targetType`, `targetId` and `occurredAt` - the row as
it is stored, with nothing added. Pages work as everywhere else in the API: `page` from 0, `size` 10 by default and 50
at most, and `sortBy` accepts `occurredAt` or `id` only, with `direction` `asc` or `desc`.

| Filter | Matches |
|---|---|
| `action` | one action name, such as `PASSWORD_RESET_BY_STAFF` |
| `outcome` | `SUCCESS` or `FAILURE` |
| `actorUserId` | everything one account did |
| `targetType` with `targetId` | everything done to one user or library |
| `from`, `to` | events at or after, and at or before, an ISO date-time such as `2026-09-20T09:30:00` |

Every filter given must match, and all of them narrow within the caller's library: an actor or target belonging to
another library matches nothing rather than reaching across. Reading the log is not itself recorded.

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

- **Single instance.** Login and password-reset rate limiting are kept in memory, per instance and per username or
  address; several instances multiply the limits. The reset limit sits behind the `PasswordResetRequestLimiter`
  interface, so a shared implementation can replace the in-memory one later.
- **Access tokens** last one hour by default, configurable through `JWT_ACCESS_TOKEN_VALIDITY`, and are not
  revoked before they expire, even by logout or a password change.
- **Refresh-token records** outlive their session by `JWT_REFRESH_TOKEN_RETENTION`, so that reuse of an old
  token is still recognised, and are then swept away. Every instance runs the sweep.
- **Audit log** is read through `GET /api/audit-events` by an administrator of the library it belongs to; there is no
  export, and nothing purges old events.
- **Reset links need an SMTP server.** Delivery is plain-text email through `MAIL_HOST`; there is no queue of its
  own, no retry after a failed send, and no bounce handling. A send that fails leaves the token usable, and the
  account holder asks again - or staff reset it with `POST /api/users/{userId}/password-reset`.
- **Forgot-password queue.** Issuing runs on one background thread with room for 500 waiting requests; beyond
  that, requests are dropped and answered with the same 202. Requests still queued at shutdown get ten seconds to
  finish. Spent reset tokens are kept for `PASSWORD_RESET_TOKEN_RETENTION` and then swept away.
- **Fine payments** are recorded by staff; there is no payment gateway.
- **Libraries** can be registered by any administrator.
- **Dates and times** in API responses carry no offset, and one business time zone applies to every library.
- **Test schemas** whose names start with `library_db_` accumulate on the machine that runs the tests.
