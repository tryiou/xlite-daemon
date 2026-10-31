# XLite Daemon

The wallet backend behind the XLite desktop and mobile GUIs. It runs one local
JSON-RPC endpoint per supported coin and sources all chain data from a provider
over HTTP — it does not run a full node.

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
| Checkstyle 14.4 | `validate` | semantics: braces, naming, import bans, file shape |
| Error Prone 2.43 | `compile` | compiler-level bug patterns |
| SpotBugs 4.9 | `process-classes` | bytecode-level bug patterns |
| JUnit 5 | `test` | unit tests |

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

The process starts a console menu unless `WALLET_MNEMONIC` and
`WALLET_PASSWORD` are set:

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

## Data providers

By default the daemon uses a hosted provider. Two environment variables or
command-line flags change that:

| setting | flag | environment |
|---|---|---|
| EXR endpoint pool (comma-separated) | `--exr-endpoint` | `EXR_ENDPOINT` |
| Development upstream | `--development-endpoint` | `BASE_URL` |
| Configuration source | `--blockchain-configuration-files` | `BLOCKCHAIN_CONFIGURATION_FILES` |

EXR is a pool of provider servers. Capabilities are probed once, health is
re-checked every 5 seconds, and a request is routed to a server that supports the
coin. When EXR is configured there is no fallback to the development upstream: a
coin no EXR server supports fails rather than silently using another path.

## RPC surface

Each coin endpoint speaks a Bitcoin-RPC-compatible subset. `help` lists it:

```
getinfo, getnetworkinfo, getfees, getblockchaininfo, getrawmempool
getblockcount, getblockhash <height>, getblock <hash>
listunspent, listtransactions, gettransaction <txid>
getaddressesbyaccount <account>, getnewaddress
importprivkey <privkey>, dumpprivkey <address>
createrawtransaction, decoderawtransaction, signrawtransaction, sendrawtransaction
gettxout <txid> <vout>
signmessage <address> <message>, verifymessage <address> <signature> <message>
stop
```

`getblockhash`, `getblock`, `gettransaction` and `sendrawtransaction` are served
from provider data, the rest from the local wallet.

## License

MIT — see [LICENSE](LICENSE).
