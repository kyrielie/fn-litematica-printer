# Project Roadmap

This document outlines the planned development phases, feature milestones, and implementation tracks for the mod.

---

## 1. Milestone Overview

| Phase | Milestone Name | Description | Status | Verification |
|---|---|---|---|---|
| **Phase 1** | `scaffold-core` | Modern Fabric 26.3 + Loom 1.17 + Java 25 template setup with split source sets, YACL config, and JUnit 5 | **DONE** | `./gradlew build` & `./gradlew test` |
| **Phase 2** | `feature-core` | Implementation of primary domain features and game listeners | **PLANNED** | In-game verification |
| **Phase 3** | `hud-ui` | In-game HUD, overlays, and GUI controls | **PLANNED** | UI functional verification |
| **Phase 4** | `anticheat-guard` | Movement packet guard, rate-limiting, and rotation convergence gates | **PLANNED** | Protocol safety checks |
| **Phase 5** | `release-polish` | Final testing, release build, and documentation completion | **PLANNED** | Full build pass |
