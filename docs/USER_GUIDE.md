# XLite Daemon — User Guide

Install, supported coins, startup variables, data-directory paths, and a request
example are in the [README](../README.md). This guide covers wallet rules,
configuration, the RPC reference, logs, and troubleshooting.

## Wallet

Wallet creation and password changes require a score of at least 9 out of 10:

- Passwords shorter than 8 characters score 0.
- 8–9 characters score 1; 10 or more score 2.
- Each class present adds 2: digit, lowercase, uppercase, and a special
  character from `~!@#$%^&*()_-+=[]{|};:',.<>?/\`.

In practice a password needs at least 8 characters and all four classes.
Unlocking an existing wallet does not apply the score.

Menu creation asks for the password twice. After creation the daemon prints the
12-word phrase once; store it offline. Flag-driven creation keeps stdout clean
and points at `--getmnemonic`.

### Mnemonic and password

`WALLET_MNEMONIC` runs the wallet from the phrase without reading or writing
`key.dat`; the wallet exists for that process only. Menu choice 3 behaves the
same way.

`WALLET_PASSWORD` decrypts `key.dat`. When no `key.dat` exists and the password
is strong enough, the daemon generates and stores a new wallet.

### Command line

```
--createdefaultwallet [password]
--createwalletmnemonic [password] [mnemonic]
--password <password>
--getmnemonic <password>
--changepassword <current> <new>
--enablerpcandconfigure
--exr-endpoint [url]
--development-endpoint [url]
--electrum-endpoint [url]
--blockchain-configuration-files <path-or-url>
--version
--help
```

| Flag | Effect |
|---|---|
| `--createdefaultwallet` | Creates `key.dat` from a new 12-word phrase, then exits |
| `--createwalletmnemonic` | Creates `key.dat` from the given phrase, then exits |
| `--password` | Logs in and runs the daemon |
| `--getmnemonic` | Prints the wallet phrase to stdout, then exits |
| `--changepassword` | Re-encrypts `key.dat` under a new password, then exits |
| `--enablerpcandconfigure` | Writes new credentials, enables every coin listener and the master endpoint, then exits |
| `--exr-endpoint` | Sets the indexing-service base URL |
| `--development-endpoint` | Sets the indexing-service base URL |
| `--electrum-endpoint` | Sets a single-indexer base URL for testing |
| `--blockchain-configuration-files` | Sets the coin-metadata source |
| `--version` | Prints the daemon version, then exits |
| `--help` | Prints the flag list, then exits |

Omitting a value after an endpoint or config-source flag falls back to its
environment variable. Wallet creation flags do nothing when `key.dat` exists.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Invalid input, failed operation, or unusable configuration |
| `4` | `--getmnemonic` could not decrypt the wallet |
| `5` | Wallet authentication failed during login |

## Data directory

| Path | Contents |
|---|---|
| `key.dat` | Encrypted wallet seed |
| `settings/config-<TICKER>.json` | Per-coin RPC settings |
| `settings/config-master.json` | Master endpoint settings |
| `backups/key-YYYYMMDDHHMMSS.dat` | Wallet moved aside before a replacement write |
| `error-YYYY-MM-DD.log.0` | Daily log; rotated files add `.1`–`.4` |
| `coinconfig-cache/` | Cached coin metadata when the source is remote |
| `.migration.lock` | Inter-process lock in the parent directory |

`key.dat` is a four-line text file: format version, Base64 salt, Base64 IV, and
the encrypted mnemonic. Encryption is AES-256-CBC with a key derived by
PBKDF2-HMAC-SHA256 at 100,000 iterations. Wallets written by older versions
(PBKDF2-HMAC-SHA1 / AES-ECB) are migrated on unlock.

Back up `key.dat` and `settings/`. Recovery requires the wallet password or the
mnemonic. A `CloudChains` directory in the same parent is migrated to
`xlite-daemon` at startup.

## Configuration

### Coin endpoints

Each supported ticker has `settings/config-<TICKER>.json`:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `rpcEnabled` | boolean | `false` | Start this coin's listener |
| `rpcUsername` | string | `""` | Basic-auth username |
| `rpcPassword` | string | `""` | Basic-auth password |
| `rpcPort` | number | `-1000` | Listener port; `-1000` derives one |
| `rpcBindAddress` | string | loopback | Interface to bind |
| `addressCount` | number | `0` | Addresses the wallet tracks |

Fees used by `getfees`, `getinfo`, and transaction building come from
blockchain-configuration-files.

A coin listener starts only when all of these hold:

- `rpcEnabled` is true.
- `rpcUsername` and `rpcPassword` are non-empty.
- `rpcBindAddress` resolves to a specific local address; wildcard addresses are
  refused.

A missing, empty, or unparseable coin config is recreated at login with random
credentials and `rpcEnabled` true. An unparseable file is preserved as
`config-<TICKER>.json.corrupt-<epoch>`. A valid existing config is never
modified.

`rpcEnabled` false means no listener. Except for `BLOCK` and `master`, the
coin's wallet is also not initialized. Those two are always enabled; a stored
`false` is rewritten to `true`.

A derived coin port is the coin's configured port plus 3. If that port is above
the valid range the offset is subtracted. The port that binds is written back to
the config file.

### Master endpoint

`settings/config-master.json` configures the endpoint that lists coins. It
defaults to port 9955, binds loopback, and starts with the wallet. Credentials
are generated at startup and written before the listener binds; `rpcEnabled` is
always true.

### Coin metadata source

| Setting | Value |
|---|---|
| Flag | `--blockchain-configuration-files` |
| Environment | `BLOCKCHAIN_CONFIGURATION_FILES` |
| Default | `https://raw.githubusercontent.com/blocknetdx/blockchain-configuration-files/master` |

The value is a local directory or a base URL. GitHub web URLs are normalized to
their raw form. A remote source is cached under `coinconfig-cache/`; when the
source is unreachable and a cache exists, the cached copy is used.

### Indexing service

The daemon talks to one indexing service. Unless overridden, the built-in
default is used. Exactly one of these may be set:

| Purpose | Flag | Environment |
|---|---|---|
| Single-indexer override | `--electrum-endpoint` | `ELECTRUM_ENDPOINT` |
| Service-node connector | `--exr-endpoint` | `EXR_ENDPOINT` |
| Direct provider | `--development-endpoint` | `BASE_URL` |

All three accept the same base-URL forms. Setting more than one, in any
combination, aborts startup. A comma-separated `EXR_ENDPOINT` uses only its
first entry.

`http://host` becomes `ws://host/ws/<TICKER>`; `https://host` becomes
`wss://host/ws/<TICKER>`. The ticker is upper-cased. Each coin opens its own
session. There is no fallback provider and no aggregation.

Startup does not wait for the service. A coin whose session is not connected
still has a listener; wallet-local methods answer, and methods that need chain
data return the unavailable error described in the JSON-RPC section.

## JSON-RPC

### Transport

- HTTP `POST` to `/` only.
- HTTP Basic auth on every request.
- Request body: JSON object with a `method` string and a `params` array.
- Response body: `result` and `error`, exactly one of which is non-null.
  The request `id` is not echoed and no `jsonrpc` member is added.

Method-level failures return HTTP 200 with an error object. Transport failures
use the HTTP status codes in the error table.

### Master endpoint

| Method | Parameters | Result |
|---|---|---|
| `ping` | none | `1` |
| `version` | none | daemon version string |
| `help` | none | method list |
| `stop` | none | shuts the process down |
| `reloadconfig` | `<ticker>` | `true`; reload runs after the reply |
| `getCoins` (`listCoins`) | none | coin metadata array |

`getCoins` rows contain `ticker`, `blockchain`, `verId`, `addressPrefix`,
`scriptPrefix`, `secretPrefix`, `coin`, `feePerByte`, `minTxFee`, `port`, and
`dustAmount`.

### Coin endpoints

| Method | Parameters |
|---|---|
| `help`, `version`, `ping`, `reloadconfig` | none |
| `getinfo`, `getblockcount`, `getnetworkinfo`, `getfees`, `getblockchaininfo` | none |
| `getblockhash` | `<height>` |
| `getblock` | `<hash>` |
| `gettransaction` | `<txid>` |
| `getrawtransaction` | `<txid> [verbose]` |
| `getrawmempool` | none |
| `listunspent` | none |
| `gettxout` | `<txid> <vout> [include_mempool]` |
| `listtransactions` | `[start] [end]`; used only when both are present |
| `historystatus` | none |
| `getaddressesbyaccount` | `<account>` |
| `getnewaddress` | none |
| `importprivkey` | `<privkey>` |
| `dumpprivkey` | `<address>` |
| `validateaddress` | `<address>` |
| `createrawtransaction` | `<inputs> <outputs>` |
| `decoderawtransaction` | `<rawtx>` |
| `signrawtransaction` | `<rawtx>` |
| `sendrawtransaction` | `<rawtx>` |
| `sendtransaction` | `<address> <amount> [dryrun]` |
| `signmessage` | `<address> <message>` |
| `verifymessage` | `<address> <signature> <message>` |

Notes:

- `reloadconfig` on a coin endpoint reloads that coin; a supplied ticker must
  match it.
- `importprivkey` adds the key to the running wallet only; it is not persisted.
- `getaddressesbyaccount` accepts `main` and returns all wallet addresses. Any
  other account returns an empty array.
- `sendtransaction` builds, signs, and broadcasts. `dryrun` set to `true`
  returns the transaction without broadcasting.
- `listtransactions` returns rows in the requested time window; unconfirmed rows
  are always included.

### Readiness and history

`getinfo.rpcready` is true while the coin's indexing-service session is
connected. `getinfo.blocks` and `getblockcount` report `0` until a tip is known.

`historystatus` reports:

| Field | Meaning |
|---|---|
| `native` | History engine attached to this coin |
| `ready` | First history pass finished |
| `complete` | Every wallet address was readable |
| `addresses` | Addresses in the snapshot |
| `rows` | Rows published |
| `tip` | Chain tip used |
| `failed` | Addresses refused by the service, with reasons |

### Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `3002` | 200 | Wallet snapshot not available for this call |
| `3003` | 200 | History is incomplete because at least one address was refused |
| `-1112` | 503 | The coin's indexing-service session is not connected |
| `-32601` | 200 | Method not found |
| `-1` | 200 | Invalid arguments; the message carries the usage line |
| `-5` | 200 | Raw transaction not found |
| `-1001` | 400 | Body is not a valid request |
| `-1002` | 400 | Path is not `/` |
| `-1003` | 400 | Method is not `POST` |
| `-1111` | 403 | Missing or wrong Basic credentials |

## Logs

The daemon writes diagnostics to stderr and to `error-YYYY-MM-DD.log.0` in the
data directory. The mnemonic and `--help` output go to stdout.

`XLITE_DAEMON_LOG_LEVEL` sets a `java.util.logging` level (default `INFO`).
`FINE`, `FINER`, and `FINEST` reach the console only; the file records `INFO`
and above. The logger level filters both outputs.

The file is appended, capped at 10 MB, and rotated up to five files per day.
Daily files older than `XLITE_DAEMON_LOG_RETENTION_DAYS` (default 30, minimum 1)
are deleted at startup.

## Troubleshooting

**`Bad password.`** — the supplied password does not decrypt `key.dat`, or the
password is too weak for wallet creation. Recovery needs the password or the
mnemonic.

**A coin has no endpoint.** Open `settings/config-<TICKER>.json` and check the
listener settings described under Configuration: `rpcEnabled` must be true and
both credentials must be set. Restart after editing.
`--enablerpcandconfigure` re-enables every coin and replaces all credentials,
so use it only when you intend to re-pair all of them.

**`getinfo.rpcready` is false.** The indexing-service session for that coin is
not connected. Check the endpoint setting and the log.

**`listtransactions` returns `3002` or `3003`.** The history snapshot is not
available, or at least one wallet address could not be read. `historystatus`
lists the refused addresses.

**The port is not the one expected.** The config file records the port that
actually bound. Read `rpcPort` from the coin's config.

**Startup fails before logging.** The platform data directory could not be
resolved. Set `XLITE_DATA_HOME` to an explicit path.

**An environment value is ignored.** A `.env` file in the working directory
takes precedence over the process environment.

## License

MIT — see [LICENSE](../LICENSE).
