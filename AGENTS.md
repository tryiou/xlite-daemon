# AGENTS.md

## Project

Java 21 / Maven project. User-facing behaviour is documented in the README and
[docs/USER_GUIDE.md](docs/USER_GUIDE.md); this file covers architecture,
commands, gates, style, and conventions.

## Architecture invariants

- **One provider.** Exactly one provider setting supplies the base URL; mixing
  them is a startup error. The URL becomes `ws(s)://…/ws/<TICKER>`, and each
  coin opens its own session. There is no fallback and no aggregation.
- **One session per coin.** History, unspents, transactions, broadcast, and the
  tip use that session. Node-only chain calls are forwarded on it too; the
  backend must answer them.
- **No local chain.** SPV/p2p chain startup is not part of the wallet path. Do
  not reintroduce a block store as a data source.
- **Local startup.** Wallets and listeners come up without waiting for the
  provider. The master endpoint starts after the coin-init pass or its timeout.
- **Readiness is per method.** `isInstanceRunning()` reflects the provider
  session. Wallet-local methods always answer; chain-data methods are gated
  while the session is down. History is reported separately.
- **Retry is permanent.** The client reconnects with capped, jittered backoff;
  there is no terminal "not served" state.
- **XRouter is reserved.** `net/xrouter/` and the Blocknet p2p extras are not
  part of the wallet data path. Keep them; do not delete or prune them.

## Contributor commands

Prerequisites and the public build commands are in the README. Contributor-only
commands:

```sh
./mvnw compile -q
./mvnw test -Dtest=KeyHandlerTest
./mvnw test -Dtest=KeyHandlerTest#testGetBaseSeedRoundTrip
./mvnw -Prewrite process-classes  # opt-in OpenRewrite
./mvnw spotless:apply
```

## Gates

| Tool | Binding | Rule |
|---|---|---|
| Spotless | process-sources | google-java-format AOSP |
| Checkstyle | validate | semantic rules, severity error |
| Error Prone | compile | findings are warnings |
| SpotBugs | process-classes | Max effort, Medium threshold, fail on error |
| JUnit 5 | test | all tests |

`./mvnw test` fails on formatting, Checkstyle, SpotBugs, or test failures.

## Style

- Spotless owns layout: 4-space indent, 100 columns, import order, trailing
  whitespace, final newline. Do not hand-format.
- Checkstyle owns what a formatter cannot see: braces, naming, banned imports,
  file shape, class structure, and method size.
- Method limits: cyclomatic complexity 10, nesting depth 4, 4 parameters,
  25 executable statements, 100 lines. Constructors are exempt from the
  parameter limit.
- No wildcard imports; no `sun` or `com.sun` imports.
- One top-level class per file, named after the file. Utility classes are final
  with a private constructor.
- Braces on every control-flow body.
- Logger pattern:
  `LogManager.getLogManager().getLogger(Logger.GLOBAL_LOGGER_NAME)`, held in
  `private static final` fields. Prefix messages with `[security]`, `[wallet]`,
  `[discovery-<TICKER>]`, etc.
- Avoid `@SuppressWarnings`; fix the code, or document the exception in the
  SpotBugs filter.
- Crypto: clear sensitive arrays with `Arrays.fill`, call
  `PBEKeySpec.clearPassword()`, use `char[]` for passphrases, `SecureRandom`
  for randomness, and `StandardCharsets.UTF_8` for byte conversion.
- Never call `printStackTrace()`; log with a level and the exception message.

## Layout

```
src/main/java/io/xlite/daemon/app/
  App.java        entry point
  Version.java
  coinconfig/     BCF metadata, cache, parameter adapter
  console/        CLI flags and interactive menu
  crypto/         KeyHandler
  net/            CoinInstance, CoinTicker, protocols, xrouter
  net/api/        JSON-RPC servers (master, per-coin, HTTP client)
  net/electrumx/  provider session, history engine
  util/           config, logging, ports, discovery, helpers
  wallet/         WalletHelper
```

Tests mirror these packages under `src/test/java/io/xlite/daemon/app/`; shared
fixtures live in `testing/`.

## Dependencies

| Library | Version | Use |
|---|---|---|
| bitcoinj-core | 0.15.10 | keys, addresses, transactions |
| Gson | 2.13.2 | JSON |
| Netty | 4.2.7.Final | HTTP servers |
| Guava | 28.2-android | collections and utilities |
| Orchid | 1.2.1 | Base64 and Hex |
| httpclient | 4.5.14 | HTTP client |
| org.json | 20250517 | config files |
| java-dotenv | 5.2.2 | `.env` |
| JUnit Jupiter | 5.11.3 | tests |
| Mockito | 5.15.2 | tests |

## Tests

- Test classes use the `Test` suffix; shared fixtures live in
  `io.xlite.daemon.app.testing`.
- Use `@TempDir`; never point tests at a real data directory.
- `ConfigHelper.setConfigDir("")` restores the platform default. Do not enable
  JUnit parallel execution: the config root is mutable static state.
- A global Jupiter extension points provider sessions at a closed loopback
  port, so tests never dial the default backend.
- Surefire attaches the ByteBuddy agent required by Mockito.

## Gotchas

- `-Prewrite` mutates sources; review `git diff` after running it.
- `target/checkstyle-cachefile` can replay old findings; delete it when
  comparing rulesets.
- Native builds default to `-march=compatibility`; do not set `native`.
- Commit subjects use `type(scope): description`.
