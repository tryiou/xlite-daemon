# AGENTS.md

## Project Overview

XLite Daemon — a multi-cryptocurrency wallet daemon built with Java 21 and Maven.
Core packages: `crypto` (wallet encryption/key management), `net` (coin networking, JSON-RPC),
`util` (config, address discovery, logging), `wallet` (wallet helpers).

## Data Providers

This is the **client-side** daemon behind the XLite wallet GUI: it serves one
local JSON-RPC port per coin (`net/api/http/master/`) and fetches all blockchain
data upstream over HTTP (`net/api/http/client/`).

- **EXR mode:** CLI `--exr-endpoint <url>` or env `EXR_ENDPOINT`
  (comma-separated pool → `EXRServerPool`). Capabilities are probed from GET
  `/xrs/heights`; health checks every 5 s; requests route to a server that
  supports the coin. Calls hit `<endpoint>/xrs/<method>` — GET `heights`/`fees`,
  POST coin-first-param methods (`getutxos`, `sendrawtransaction`, …). Once EXR
  is configured there is **NO fallback** to BASE_URL — requests for coins no EXR
  server supports fail hard.
- **Legacy mode (default):** `BASE_URL` = `https://xliterevp.mywire.org/` in
  `App.java`; override with `--development-endpoint`. POST `/` with
  `{method, params}` JSON.
- Upstream chain: exrproxy `/xrs/<service>` → plugin-adapter xrm methods →
  utxo-plugin containers.
- `net/xrouter/` and the `xrm*` handling in `BlocknetPeerGroup` are currently
  disabled XRouter-over-p2p paths (commented out at `CoinInstance.java:517-557`)
  — **work-in-progress, NOT abandoned**: never delete, prune, or "clean up" them;
  treat every Blocknet p2p/XRouter extra (serializers, packet magic, peer-group
  plumbing) as live WIP. Any earlier "dead legacy" wording here was wrong; the
  workspace-root rule takes precedence.

# Java — use jabba
source ~/.jabba/jabba.sh && jabba use graalvm_community@21.0.2

## Build & Test Commands

```bash
# Compile
mvn compile -q

# Run all tests
mvn test

# OpenRewrite code cleanup (opt-in; NOT part of routine builds)
mvn -Prewrite process-classes

# Run a single test class
mvn test -pl . -Dtest=KeyHandlerTest

# Run a single test method
mvn test -pl . -Dtest=KeyHandlerTest#testGetBaseSeedRoundTrip

# Build shaded JAR
mvn package -q

# Build GraalVM native image
mvn package -Pnative -Pnative-fast -q

# Requirements: Java 21 (jabba: graalvm_community@21.0.2), Maven 3.8.6+
```

## Code Style

### Imports

- Group order: third-party libraries, then `java.*`, then `javax.*`
- Wildcard imports are acceptable for large groups (e.g., `java.io.*`, `org.bitcoinj.core.*`)
- No unused imports; OpenRewrite cleanup is opt-in via `mvn -Prewrite process-classes`

### Formatting

- 4-space indentation, no tabs
- Opening braces on same line
- Blank line between methods, between logical sections within methods
- No trailing whitespace

### Logging

Every class uses this logger pattern:

```java
private final static LogManager LOGMANAGER = LogManager.getLogManager();
private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
```

Log levels: `SEVERE` for critical failures, `WARNING` for recoverable errors,
`INFO` for operational events, `FINER` for debugging (e.g., wrong password).

Log messages use bracketed prefixes: `[security]`, `[discovery-BLOCK]`, `[wallet]`.

### Naming

- Classes: `PascalCase`
- Methods/variables: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Test classes: `<ClassUnderTest>Test.java`
- Test methods: `test<Behavior>` (e.g., `testGetBaseSeedRoundTrip`, `testDiscovery_FindsUsedAddresses`)
- Tests use `@TestMethodOrder(OrderAnnotation.class)` + `@Order(n)` for sequencing
- Use `static import org.junit.jupiter.api.Assertions.*` for assertions

### Error Handling & Security

- Crypto ops: try/finally with `Arrays.fill(bytes, (byte) 0)` to clear sensitive data
- Call `PBEKeySpec.clearPassword()` after key derivation
- Never use `e.printStackTrace()` — use `LOGGER.level(msg + e.getMessage());`
- Catch specific exceptions before generic `Exception`
- `RuntimeException` for unrecoverable state; return `null`/`false` for expected failures
- AES-CBC + random IV for new encryption; ECB only for legacy migration
- PBKDF2WithHmacSHA256, 100k iterations; `SecureRandom.getInstanceStrong()` for RNG
- Always use `StandardCharsets.UTF_8` for `getBytes()` calls
- Passphrases use `char[]` — `String` is unsupported (immutable, cannot be wiped)

## Project Structure

```
src/main/java/io/cloudchains/app/
  crypto/        KeyHandler (wallet encryption), LoginUtils (auth)
  net/           CoinInstance (coin lifecycle), JSON-RPC servers, protocols/
  util/          ConfigHelper, AddressDiscoveryService, UTXO, logging
  wallet/        WalletHelper
  App.java       Main entry point

src/test/java/
  KeyHandlerTest, CoinInstanceTest, ConfigHelperTest,
  AddressDiscoveryServiceTest, LoginUtilsTest, TestHelper
```

## Key Dependencies

| Library | Purpose |
|---------|---------|
| bitcoinj-core 0.15.10 | Bitcoin/crypto, MnemonicCode, ECKey |
| Gson 2.13.2 | JSON serialization |
| Netty 4.2.7 | HTTP servers, networking |
| Guava 28.2-android | Joiner, Preconditions, AtomicDouble |
| Orchid 1.2.1 | Hex/Base64 encoders |
| httpclient 4.5.14 | HTTP client |
| json 20250517 | JSONObject/JSONArray |
| java-dotenv 5.2.2 | .env file support |
| JUnit Jupiter 5.11.3 | Test framework |
| Mockito 5.15.2 | Test mocking |

## Commit Message Style

Recent commits use conventional commits: `type(scope): description`

Types: `feat`, `fix`, `chore`, `refactor`, `build`, `security`, `test`.

Some older commits use `[category] description` style (e.g., `[security] Upgrade wallet encryption`).

## Things to Watch For

- The `rewrite-maven-plugin` no longer runs in the default build (it cost ~8s
  per build); it executes only under the `-Prewrite` profile and may auto-modify
  imports and formatting. Run it before releases or style sweeps, and always
  review `git diff` afterwards.
- `ConfigHelper.CONFIG_DIR` is a mutable static used to override config path in tests.
- Tests use `@TempDir` (JUnit 5 auto-cleanup); never run with parallel execution
  due to mutable `ConfigHelper.CONFIG_DIR` static state.
- Mockito requires ByteBuddy agent (configured in pom.xml surefire plugin).
- App reads `.env` via java-dotenv (`App.getEnv()` wraps `Dotenv`).
- Javadoc uses `<p>` tags and `{@code}` inline; section dividers use `// ===` / `// ---`.
- The enforcer plugin requires Java 21 and Maven 3.8.6+.
