# AGENTS.md

## Project Overview

XLite Daemon — a multi-cryptocurrency wallet daemon built with Java 21 and Maven.
Core packages: `crypto` (wallet encryption/key management), `net` (coin networking, JSON-RPC),
`util` (config, address discovery, logging), `wallet` (wallet helpers).

## Data Providers

This is the **client-side** daemon behind the XLite wallet GUI: it serves one
local JSON-RPC port per coin (`net/api/http/master/`) and fetches all blockchain
data from one remote provider over the **native ElectrumX protocol**
(`net/electrumx/`). The local RPC surface is a contract for the GUI, BlockNet
XBridge and go-xbridge; provider work never changes it.

- **One provider.** Choose exactly one of `--exr-endpoint`/`EXR_ENDPOINT`
  (service node) or `--development-endpoint`/`BASE_URL` (direct
  plugin-adapter). Setting both is a startup error. There is **NO fallback**,
  no pool aggregation and no per-method routing.
- **One protocol.** The chosen base URL becomes
  `ws(s)://<host>/ws/<TICKER>`; the daemon opens one session per coin and uses
  it for history, unspents, transactions, broadcast and the tip. It does not
  call `/xrs` for wallet data.
- **`--electrum-endpoint`/`ELECTRUM_ENDPOINT`** is a single-indexer test
  override (absolute WebSocket base). It cannot be combined with the other two.
- The node answers `getblock`, `getblockhash` and `getrawmempool` on that same
  socket from its coin daemon; stock ElectrumX has no equivalent, and the local
  RPC must keep them for XBridge.
- Upstream chain: xlite-daemon → plugin-adapter `/ws/<TICKER>` → stock
  ElectrumX indexer (TCP 9000). `exrproxy` forwards `/ws/<TICKER>` to the
  adapter when the connector is a service node.
- **Startup never waits for the provider.** Wallets, configs and per-coin
  listeners come up locally; the master endpoint is announced first. History
  sync and address discovery run on the coin's workers. Readiness
  (`isInstanceRunning()` / `getinfo.rpcready`) is the provider session; history
  is derived (`historystatus`, and `listtransactions` 3002 until complete).
  Nothing here may block on a socket.
- **Probing is permanent.** A failed dial/handshake fails the request at once
  (never queues until a 60 s timeout) and the client keeps retrying with
  capped, jittered backoff. There is no terminal "not served" state: a coin the
  backend adds or restores enables itself on the next successful connect.
- **RPC availability is per method.** Local wallet methods always answer; only
  chain-data methods return the `503 -1112` gate while the coin is not ready.

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
./mvnw compile -q

# Run all tests plus every gate (Spotless, Checkstyle, Error Prone, SpotBugs)
./mvnw test

# OpenRewrite code cleanup (opt-in; NOT part of routine builds)
./mvnw -Prewrite process-classes

# Run a single test class
./mvnw test -Dtest=KeyHandlerTest

# Run a single test method
./mvnw test -Dtest=KeyHandlerTest#testGetBaseSeedRoundTrip

# Build shaded JAR
./mvnw package -q

# Build GraalVM native image (fast profile skips tests)
./mvnw package -Pnative
./mvnw package -Pnative-fast

# Requirements: Java 21 (jabba: graalvm_community@21.0.2), Maven 3.8.6+
```

## Code Style

The rules are enforced, not advised: `./mvnw test` fails on any formatting,
Checkstyle, SpotBugs or test violation; Error Prone findings are reported as
warnings.

### Formatting

Owned entirely by Spotless (google-java-format, AOSP style): 4-space indent,
100 columns, import order and grouping, no trailing whitespace, final newline.
Do not hand-format; run `./mvnw spotless:apply` and review the result.

Checkstyle deliberately does **not** duplicate layout rules (indentation,
whitespace, import order, line length) — two tools answering the same question
produced 15 000 conflicts. It enforces only what a formatter cannot see:
braces, naming, banned imports, file shape, class structure, and method size.

### Method size

A method may have at most cyclomatic complexity 10, nesting depth 4, four
parameters, 25 executable statements and 100 lines. Over any of them, split it
into the phases it already has: name each step rather than moving code into a
helper that only the same method calls. A constructor may take as many
arguments as its value object has fields; that is its schema, not a call-site
readability problem.

### Imports

- No wildcard imports. Static JUnit/Mockito member imports are allowed.
- No unused, redundant or `sun`/`com.sun` imports.

### Structure

- `private static final` for constants (Checkstyle `ModifierOrder`).
- Braces on every control-flow body.
- One top-level class per file, named after the file.
- Utility classes are `final` with a private constructor.
- Classes that are never extended are `final`; a constructor that validates and
  throws must not be overridable.

### Logging

Every class uses this logger pattern:

```java
private static final LogManager LOGMANAGER = LogManager.getLogManager();
private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
```

Log levels: `SEVERE` for critical failures, `WARNING` for recoverable errors,
`INFO` for operational events, `FINER` for debugging (e.g., wrong password).

Log messages use bracketed prefixes: `[security]`, `[discovery-BLOCK]`, `[wallet]`.

### Naming

- Classes: `PascalCase`
- Methods/variables: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Test classes: `<ClassUnderTest>Test.java`, in the package of the class under
  test; shared fixtures live in `io.xlite.daemon.app.testing`
- Test methods: `camelCase` behaviour sentences (`readsCommandLengthAndChecksum`);
  older classes still use the `test<Behavior>` prefix

### Static analysis

- Error Prone runs on every compile. The Blocknet/XRouter packages are excluded
  from its unused-code rules because that path is unfinished work in progress;
  the exclusion and its removal condition are commented in `pom.xml`.
- SpotBugs runs at maximum effort, Medium threshold, on every build. Anything it
  cannot be told about mechanically belongs in
  `config/spotbugs/spotbugs-exclude.xml` with a reason.
- No `@SuppressWarnings` and no per-file suppressions: fix the code, or document
  the design decision in the filter.

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
src/main/java/io/xlite/daemon/app/
  crypto/        KeyHandler (wallet encryption/key management)
  net/           CoinInstance (coin lifecycle), JSON-RPC servers, protocols/
  util/          ConfigHelper, AddressDiscoveryService, UTXO, logging
  wallet/        WalletHelper
  App.java       Main entry point

src/test/java/io/xlite/daemon/app/
  AppTest, coinconfig/, net/, testing/, util/, wallet/
  (shared fixtures in testing/TestHelper)
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
- `ConfigHelper.getConfigDir()`/`setConfigDir()` override the config root in tests.
- Tests use `@TempDir` (JUnit 5 auto-cleanup); never run with parallel execution
  due to the mutable config-root static in `ConfigHelper`.
- Mockito requires ByteBuddy agent (configured in pom.xml surefire plugin).
- App reads `.env` via java-dotenv (`App.getEnv()` wraps `Dotenv`).
- Javadoc uses `<p>` tags and `{@code}` inline; section dividers use `// ===` / `// ---`.
- The enforcer plugin requires Java 21 and Maven 3.8.6+.
- `mvn` on this host prints French, so Checkstyle messages are in French.
- `target/checkstyle-cachefile` caches by ruleset hash; delete it when comparing
  two rulesets, or it replays the old findings.
