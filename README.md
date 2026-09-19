# interview-prep-app

The interview-prep application: Spring Boot 4 (Java 25) API, React + TypeScript front end,
PostgreSQL. Built in Phase 1; this repository is empty until then.

Planned layout:

| Path | What it will hold |
| --- | --- |
| `backend/` | Spring Boot modular monolith: curriculum, planner, progress, gamification, evidence, (later) AI. |
| `frontend/` | React + Vite SPA: dashboard, weekly plan, topic pages, Mermaid diagrams, PGlite SQL practice. |
| `deploy/` | Docker Compose for the Lightsail VM: app, PostgreSQL (data on `/data/postgres`), WAL-G backups. |
| `.github/workflows/` | Build, test and publish on merge to `main`. |

Related repositories: `interview-prep-content` (curriculum) and `interview-prep-infra` (AWS).
