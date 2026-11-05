# Fabric Mod Template (Minecraft 26.3)

A modern, production-grade Fabric mod scaffold targeting **Minecraft 26.3** with **Kotlin**, **Fabric Loom 1.17**, **Java 25**, split client environment source sets, **YetAnotherConfigLib (YACL)**, and pure **JUnit 5 Jupiter** unit testing.

---

## Technical Specifications

| Component | Target Version |
|---|---|
| **Minecraft** | `26.3` (compatible with `26.x`) |
| **Mapping Layer** | **None (Official Mojang Names)** — unmapped since 26.1 |
| **Java** | `Java 25` |
| **Fabric Loader** | `0.19.5` |
| **Fabric Loom** | `1.17-SNAPSHOT` (Loom 1.17.21) |
| **Fabric Language Kotlin** | `1.14.2+kotlin.2.4.21` (Kotlin 2.4.0) |
| **Fabric API** | `0.163.0+26.3` |
| **Config Library** | `YetAnotherConfigLib v3.9.7+26.3-fabric` + Gson |
| **ModMenu** | `21.0.0` |
| **Unit Testing** | `JUnit Jupiter 6.1.2` |

---

## Quickstart & Commands

```bash
# Build the mod JAR into build/libs/
./gradlew build --no-daemon

# Run pure JVM unit tests
./gradlew test --no-daemon

# Launch client development environment
./gradlew runClient --no-daemon
```

---

## Project Structure

```
fabric-mod-template/
├── src/
│   ├── main/
│   │   ├── kotlin/kyrielie/templatemod/
│   │   │   └── TemplateMod.kt            # Common ModInitializer
│   │   └── resources/
│   │       ├── fabric.mod.json           # Mod metadata & entrypoints
│   │       └── assets/templatemod/icon.png
│   ├── client/
│   │   └── kotlin/kyrielie/templatemod/client/
│   │       ├── TemplateModClient.kt      # ClientModInitializer & keybindings
│   │       └── config/
│   │           ├── TemplateModConfig.kt  # Gson config data class & ConfigManager
│   │           └── ModMenuIntegration.kt # YACL GUI configuration screen
│   └── test/
│       └── kotlin/kyrielie/templatemod/
│           └── TemplateModTest.kt        # JUnit 5 pure logic unit tests
├── build.gradle                          # Loom 1.17, Kotlin 2.4, Java 25
├── gradle.properties                     # Version definitions
├── settings.gradle                       # Root project name
├── ARCHITECTURE.md                       # Deep architectural documentation
├── CLAUDE.md                             # AI pairing agent operating rules
└── LOCAL_DEV_REFERENCES.md               # Sibling repository index & reference guide
```
