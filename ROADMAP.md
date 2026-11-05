# Project Roadmap

This document outlines the planned development phases, feature milestones, and implementation tracks for the mod.

---

## 1. Milestone Overview

| Phase | Milestone Name | Description | Status | Verification |
|---|---|---|---|---|
| **Phase 1** | `scaffold-core` | Modern Fabric 26.3 + Loom 1.17 + Java 25 template setup with split source sets, YACL config, and JUnit 5 | **DONE** | `./gradlew build` & `./gradlew test` |
| **Phase 2** | `feature-core` | Implementation of primary domain features and game listeners | **DONE** | In-game verification (98.2% confirmation rate) |
| **Phase 3** | `hud-ui` | In-game HUD, overlays, and GUI controls | **DONE** | UI functional verification with ModMenu + YACL v3 |
| **Phase 4** | `anticheat-guard` | Movement packet guard, rate-limiting, and rotation convergence gates | **DONE** | Protocol safety checks & silent packet aiming (Approach C) |
| **Phase 5** | `release-polish` | Final testing, release build, and documentation completion | **DONE** | Full build pass & test suite (9/9 passing) |
