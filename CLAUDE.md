## Operating Rules for AI Agents

1. **Approach**:
   - Read existing code and configs before modifying. Don't re-read files unless changed.
   - Be thorough in reasoning, concise in output.
   - Do not guess Minecraft APIs, versions, flags, commit SHAs, or package names. Verify against source or `LOCAL_DEV_REFERENCES.md`.
   - Never defeat or bypass access controls, permission checks, or network/sandbox boundaries.

2. **Communication & Style**:
   - No sycophantic openers or closing fluff.
   - No emojis or em-dashes.
   - Create clickable links with the `file://` scheme for modified files, classes, and symbols.

3. **Technical Constraints**:
   - Fabric Loader 0.19.5, Minecraft 26.3, Loom 1.17, Java 25.
   - Unmapped Mojang symbols — no Yarn or Intermediary namespaces.
   - Split source sets: `src/main` (common) MUST NOT reference `src/client`.
   - Unit tests run in a standalone JVM: keep core logic pure (Decision D1 pattern) to avoid uninitialized Minecraft registry crashes.

4. **Verification**:
   - Always run `./gradlew test --no-daemon` and `./gradlew build --no-daemon` before concluding work.
