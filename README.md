# XLite Daemon

Wallet engine for the XLite desktop wallet. It stores the encrypted seed,
derives addresses, builds and signs transactions, and serves one local JSON-RPC
endpoint per coin. Chain state comes from a remote indexing service; the daemon
does not download blockchains.

Supported tickers: `BLOCK`, `BTC`, `BCH`, `BTX`, `LTC`, `DASH`, `DGB`, `DOGE`,
`SYS`, `PIVX`, `UNO`, `PKOIN`, `RVN`, `XVG`. Mainnet only.

## Install

| Platform | Binary |
|---|---|
| Linux x64 | `xlite-daemon-linux64` |
| Linux arm64 | `xlite-daemon-linux-arm64` |
| macOS Intel | `xlite-daemon-osx64` |
| macOS Apple silicon | `xlite-daemon-osx-arm64` |
| Windows x64 | `xlite-daemon-win64.exe` |

Linux and macOS:

```sh
BINARY=xlite-daemon-linux64   # replace with the file for your platform
chmod +x "$BINARY"
./"$BINARY"
```

Windows:

```bat
xlite-daemon-win64.exe
```

## First start

Without credentials the daemon opens a console menu:

```
1 - Create new wallet
2 - Decrypt wallet
3 - Import from mnemonic
4 - Quit
```

On a terminal, creating a wallet asks for the password twice, then prints the
recovery phrase once. Store it offline.

To start without the menu:

| Variable | Meaning |
|---|---|
| `WALLET_PASSWORD` | Password of the wallet in the data directory |
| `WALLET_MNEMONIC` | BIP39 phrase to run from |

`WALLET_MNEMONIC` takes precedence. Variables are read from a `.env` file in the
working directory first, then from the process environment.

Password rules, mnemonic persistence, command-line flags, and exit codes are in
[docs/USER_GUIDE.md](docs/USER_GUIDE.md#wallet).

## Data

| Platform | Path |
|---|---|
| Windows | `%APPDATA%\xlite-daemon` |
| macOS | `~/Library/Application Support/xlite-daemon` |
| Linux | `~/.config/xlite-daemon` |

`XLITE_DATA_HOME` sets the parent directory; the daemon creates `xlite-daemon`
inside it. The wallet is `key.dat`; RPC settings are under `settings/`.

Contents, backup, encryption, and migration are in
[docs/USER_GUIDE.md](docs/USER_GUIDE.md#data-directory).

## Use

Each coin endpoint is HTTP Basic-authenticated JSON-RPC on `/`. Credentials and
ports are in `settings/config-<TICKER>.json`; the master endpoint lists coins.

```sh
curl -s -u "$RPC_USER:$RPC_PASSWORD" "http://127.0.0.1:$RPC_PORT" \
  -H "Content-Type: application/json" \
  -d '{"method":"getinfo","params":[]}'
```

Method lists and error codes are in the
[RPC reference](docs/USER_GUIDE.md#json-rpc); configuration keys are in
[configuration](docs/USER_GUIDE.md#configuration).

## Build

Requires JDK 21; a native binary requires GraalVM CE 21.

```sh
./mvnw test
./mvnw package
./mvnw package -Pnative
```

On Windows use `mvnw.cmd`.

Contributor guide: [AGENTS.md](AGENTS.md).

## License

MIT — see [LICENSE](LICENSE).
