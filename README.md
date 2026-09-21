# interview-prep-app

The interview-prep application: Spring Boot 4 (Java 25) API, React + TypeScript front end,
PostgreSQL. Runs on one Lightsail VM in Mumbai, reachable only over Tailscale at
`https://interview-prep.tail82d7a5.ts.net`.

| Path | What it holds |
| --- | --- |
| `backend/` | Spring Boot modular monolith: curriculum, evidence, learner, (later) planner, progress, gamification, AI. |
| `frontend/` | React + Vite SPA. Built into the backend jar, so one process serves the UI and the API. |
| `deploy/` | Docker Compose for the VM: app and PostgreSQL, with the database on the dedicated `/data` disk. |
| `.github/workflows/` | Build and test on every push and pull request. |

Related repositories: `interview-prep-content` (curriculum) and `interview-prep-infra` (AWS).

## Running it on your machine

You need JDK 25 and Docker.

```bash
docker compose -f docker-compose.dev.yml up -d   # PostgreSQL on 127.0.0.1:5432
cd backend && ./mvnw spring-boot:run             # API and the built UI on :8080
```

On the VM, Tailscale tells the app who each request is from. There is no Tailscale in front of it
on a laptop, so name yourself:

```bash
APP_LEARNERS='dev@example.com=you:You' APP_IDENTITY_DEV_LOGIN=dev@example.com ./mvnw spring-boot:run
```

`APP_IDENTITY_DEV_LOGIN` is a development convenience and the app says so loudly at startup. It must
never be set on the VM.

To load the real curriculum locally, build a bundle in the content repository and point the app
at it. Without one the app starts with an empty curriculum, which is fine for most work:

```bash
( cd ../interview-prep-content && python3 scripts/bundle.py --out dist --git-sha local )
APP_CONTENT_BUNDLE_DIR=../../interview-prep-content/dist ./mvnw spring-boot:run
```

For front-end work, run Vite's dev server alongside it for hot reload — it proxies `/api` to
port 8080:

```bash
cd frontend && npm install && npm run dev        # UI on :5173
```

## Building and testing

```bash
cd backend && ./mvnw verify
```

That one command does everything CI does: installs the pinned Node, builds the React app, runs
its tests, compiles the backend, checks the module boundaries, and runs every migration against a
real PostgreSQL in a container. Docker must be running.

The container image packages the jar that command produces rather than building its own copy, so
build it second:

```bash
cd backend && ./mvnw package && cd .. && docker build .
```

## How it fits together

The React app is built into `BOOT-INF/classes/static` inside the jar and served by Spring on the
same port as the API. One container instead of two matters here: the VM has under 2 GB of RAM.
Paths that are not files and not `/api` or `/actuator` are handed to `index.html` so the React
router can resolve them — including dotted ones like `/units/ds.transactions.idempotency-keys`.

Module boundaries are enforced by Spring Modulith and checked by a test. Modules refer to each
other by id rather than by JPA association, which is what keeps them separable later.

Flyway owns the schema; Hibernate never alters it. Migrations land alongside the code that reads
them, so a table arrives in the same change as its first query.

Personal data — progress, notes, project write-ups — lives only in PostgreSQL, never in Git.
