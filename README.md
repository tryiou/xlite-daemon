# XLite Daemon

The wallet backend behind the XLite desktop and mobile GUIs. It runs one local
JSON-RPC endpoint per supported coin and sources all chain data from one
provider over the native ElectrumX protocol — it does not run a full node.

Chain parameters are **not** compiled in: address prefixes, ports, fees, dust
thresholds and the coin list are read at runtime from
[blockchain-configuration-files](https://github.com/blocknetdx/blockchain-configuration-files).
Adding a coin means adding a configuration there, not changing this code.

## Requirements

- JDK 21 (GraalVM CE for a native image)
- Maven 3.8.6+ — the wrapper pins its own version, so `./mvnw` is enough

```bash
# Only needed if you build outside the wrapper:
source ~/.jabba/jabba.sh && jabba use graalvm_community@21.0.2
```

## Build and test

```bash
chmod +x mvnw

./mvnw test                    # gates + unit tests (see below)
./mvnw package -DskipTests     # shaded JAR
./mvnw package -Pnative       # native image at target/xlite-daemon
./mvnw package -Pnative-fast  # native image, tests skipped
```

`./mvnw test` is the whole gate, because every check is bound to the build
lifecycle:

| check | phase | what it enforces |
|---|---|---|
| Spotless + google-java-format (AOSP) | `process-sources` | layout: 4-space indent, 100 columns, import order |
| Checkstyle 14.4 | `validate` | semantics: braces, naming, import bans, file shape, method size |
| Error Prone 2.43 | `compile` | compiler-level bug patterns (reported as warnings) |
| SpotBugs 4.9 | `process-classes` | bytecode-level bug patterns |
| JUnit 5 | `test` | unit tests |

Checkstyle also holds method size: cyclomatic complexity 10, nesting depth 4,
four parameters, 25 executable statements, 100 lines. A method over those is
decomposed, not annotated.

SpotBugs runs at maximum effort and the Medium threshold. Its reviewed
deviations live in `config/spotbugs/spotbugs-exclude.xml`, one entry per case
with the reason. Checkstyle's rules and the reasoning behind every exclusion are
in `config/checkstyle/checkstyle.xml`.

To fix formatting rather than read a failure:

```bash
./mvnw spotless:apply
```

## Running

```bash
./target/xlite-daemon
```

or, from a JAR build:

```bash
./mvnw exec:java
```

The process starts a console menu unless `WALLET_MNEMONIC` or
`WALLET_PASSWORD` is set:

```bash
export WALLET_MNEMONIC="word1 word2 ... word12"
export WALLET_PASSWORD="..."
```

## Configuration

One configuration file per coin, plus one for the master endpoint:

```
Windows   %APPDATA%\xlite-daemon\settings\config-*.json
macOS     ~/Library/Application Support/xlite-daemon/settings/config-*.json
Linux     ~/.config/xlite-daemon/settings/config-*.json
```

Set `XLITE_DATA_HOME` to move that root. The daemon aborts at startup when no
override is set and the platform default cannot be resolved (for example
Windows with `%APPDATA%` unset).

Each coin's RPC credentials, bind address and port live in its own file; the
daemon generates credentials on first run and never listens off loopback unless
an operator configures it otherwise.

The master endpoint steps to the next free port of its window when the
configured one is held, and writes the port it bound back to
`config-master.json` before it announces readiness. Two daemons, or a restart
while the previous one is still shutting down, therefore keep a usable master
endpoint instead of the second one starting without one.

## Data providers

The daemon talks to exactly one provider. Set one of these:

| setting | flag | environment |
|---|---|---|
| Service-node connector | `--exr-endpoint` | `EXR_ENDPOINT` |
| Direct provider (plugin-adapter) | `--development-endpoint` | `BASE_URL` |
| Single-indexer test override | `--electrum-endpoint` | `ELECTRUM_ENDPOINT` |
| Configuration source | `--blockchain-configuration-files` | `BLOCKCHAIN_CONFIGURATION_FILES` |

The chosen provider is one node. The daemon derives that node's native route
from it — `http(s)://host` becomes `ws(s)://host/ws/<TICKER>` — and speaks
stock ElectrumX on that single session for history, unspents, transactions,
broadcast and the chain tip. There is no second transport and no fallback:
mixing BASE_URL, EXR_ENDPOINT or ELECTRUM_ENDPOINT is a startup error, and a
coin the node cannot serve fails instead of silently using another path.

With none of them set, the daemon uses `https://xliterevp.mywire.org/`.

`--electrum-endpoint` exists only for testing one indexer without a node; it
takes an absolute WebSocket base and cannot be combined with the other two.

The node answers the three methods stock ElectrumX lacks (`getblock`,
`getblockhash`, `getrawmempool`) on that same socket, from its coin daemon.
That keeps the local RPC surface complete for BlockNet XBridge while the
wallet's data still comes from one provider.

### Availability

Startup is local. A coin's wallet, configuration and JSON-RPC listener come up
without waiting for the provider; the master endpoint is announced before any
coin has to answer. Provider work — the initial history sync and the address
discovery that finds a wallet's used addresses — runs afterwards, on the coin's
own workers.

The provider is probed for the life of the process. A failed dial or handshake
fails the request immediately instead of parking it behind a socket that may
never arrive, and the client keeps retrying with capped, jittered backoff
(1 s → 30 s). Nothing is terminal: a coin the backend does not serve yet, or
stops serving, comes online by itself when a session succeeds, including one
that appears minutes after launch.

The local RPC reflects that state. `getinfo` always answers and reports
`rpcready`: `true` while the coin's provider session is connected. History is
separate: `historystatus` reports the snapshot and `listtransactions` answers
`3002` until it is complete. Methods answered from local wallet state (`help`,
`version`, `ping`, `getinfo`, `getnetworkinfo`, `getfees`,
`getblockchaininfo`, `getblockcount`, `getaddressesbyaccount`,
`getnewaddress`, `importprivkey`, `dumpprivkey`, `validateaddress`,
`historystatus`, `signmessage`, `verifymessage`) keep working; methods that
read chain data answer `503 -1112 "This coin is temporarily unavailable."`
while the session is not connected.

## RPC surface

The master endpoint (`config-master.json`, default port 9955) serves:

```
ping, version, help, stop, reloadconfig
getCoins (alias listCoins)
```

Each coin endpoint speaks a Bitcoin-RPC-compatible subset. `help` lists it:

```
help, version, ping, reloadconfig
getinfo, getnetworkinfo, getfees, getblockchaininfo, getblockcount
getblockhash <height>, getblock <hash>, getrawmempool
gettransaction <txid>, getrawtransaction <txid> [verbose]
listunspent, gettxout <txid> <vout>, listtransactions [start] [end]
historystatus
getaddressesbyaccount <account>, getnewaddress
importprivkey <privkey>, dumpprivkey <address>, validateaddress <address>
createrawtransaction, decoderawtransaction, signrawtransaction, sendrawtransaction
sendtransaction <address> <amount> [dryrun]
signmessage <address> <message>, verifymessage <address> <signature> <message>
```

`stop` is master-only. Chain reads and broadcasts are served from the
provider's native session; the rest come from the local wallet.
`sendtransaction` builds, signs and broadcasts under a per-coin lock.

## License

MIT — see [LICENSE](LICENSE).
