# AGENTS.md

## Project Overview

XLite Daemon — a multi-cryptocurrency wallet daemon built with Java 21 and Maven.
Core packages: `crypto` (wallet encryption/key management), `net` (coin networking, JSON-RPC),
`util` (config, address discovery, logging), `wallet` (wallet helpers).

## Build & Test Commands

```bash
# Compile
mvn compile -q

# Run all tests
mvn test

# Run a single test class
mvn test -pl . -Dtest=KeyHandlerTest

# Run a single test method
mvn test -pl . -Dtest=KeyHandlerTest#testGetBaseSeed

# Build shaded JAR
mvn package -q

# Requirements: Java 21, Maven 3.8.6+
```

## Code Style

### Imports

- Group order: third-party libraries, then `java.*`, then `javax.*`
- Wildcard imports are acceptable for large groups (e.g., `java.io.*`, `org.bitcoinj.core.*`)
- No unused imports; OpenRewrite cleanup runs on `mvn compile`

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
- Test methods: `test<Behavior>` (e.g., `testGetBaseSeed`, `testLegacyWalletMigration`)

### Error Handling

- Crypto operations: use try/finally to clear sensitive byte arrays with `Arrays.fill(bytes, (byte) 0)`
- Use `PBEKeySpec.clearPassword()` after key derivation
- Do not use `e.printStackTrace()` — use `LOGGER.log(Level.WARNING, "message", e)` instead
- Catch specific exceptions (`BadPaddingException`) before generic `Exception`
- `RuntimeException` for unrecoverable state; return `null` or `false` for expected failures

### Security Conventions

- AES-CBC with random IV for all new encryption; ECB only for legacy decryption
- PBKDF2 with `PBKDF2WithHmacSHA256`, 100k iterations for current format
- `SecureRandom.getInstanceStrong()` for all cryptographic RNG
- Explicit `StandardCharsets.UTF_8` in all `getBytes()` calls
- Clear sensitive data in `finally` blocks — never rely on GC alone

## Project Structure

```
src/main/java/io/cloudchains/app/
  crypto/        KeyHandler (wallet encryption), LoginUtils (auth)
  net/           CoinInstance (coin lifecycle), JSON-RPC servers, protocols/
  util/          ConfigHelper, AddressDiscoveryService, UTXO, logging
  wallet/        WalletHelper
  App.java       Main entry point

src/test/java/
  KeyHandlerTest.java, CoinInstanceTest.java, ConfigHelperTest.java,
  AddressDiscoveryServiceTest.java, LoginUtilsTest.java
```

## Key Dependencies

| Library | Purpose |
|---------|---------|
| bitcoinj-core 0.14.7 | Bitcoin/crypto primitives, MnemonicCode, ECKey |
| Gson 2.13.2 | JSON serialization |
| Netty 4.2.7 | HTTP servers, networking |
| Guava (via bitcoinj) | Joiner, Preconditions, utilities |
| JUnit Jupiter 5.11.3 | Test framework |
| Mockito 5.15.2 | Test mocking |

## Commit Message Style

Recent commits use conventional commits: `type(scope): description`

Types: `feat`, `fix`, `chore`, `refactor`, `build`, `security`, `test`.

Some older commits use `[category] description` style (e.g., `[security] Upgrade wallet encryption`).

## Things to Watch For

- The `rewrite-maven-plugin` runs on `mvn compile` and may auto-modify imports and formatting.
  Always review `git diff` after compiling.
- `ConfigHelper.CONFIG_DIR` is a mutable static used to override config path in tests.
- Tests create temp directories and write wallet files; `@AfterEach` handles cleanup.
- The enforcer plugin requires Java 21 and Maven 3.8.6+.
