# XLite Daemon User Guide

## Table of Contents
1. [Overview](#overview)
2. [System Requirements](#system-requirements)
3. [Installation](#installation)
4. [Configuration](#configuration)
5. [Usage](#usage)
6. [Supported Cryptocurrencies](#supported-cryptocurrencies)
7. [Security Features](#security-features)
8. [Troubleshooting](#troubleshooting)
9. [API Reference](#api-reference)
10. [Advanced Configuration](#advanced-configuration)

## Overview

The XLite Daemon is a multi-cryptocurrency wallet backend that provides secure wallet management, blockchain connectivity, and JSON-RPC API services for various cryptocurrencies. It serves as the backend infrastructure for the XLite wallet application.

### Key Features
- **Multi-Currency Support**: every coin the configured
  blockchain-configuration-files manifest lists, with no coin list compiled in
- **Secure Wallet Management**: Industry-standard encryption with PBKDF2 key derivation
- **Address Discovery**: Automatic detection of used addresses for wallet recovery
- **JSON-RPC API**: Comprehensive API for wallet operations and blockchain queries
- **XRouter Integration**: cross-chain messaging over the Blocknet p2p network.
  This path is present but disabled in the current build; see `AGENTS.md`
- **Native Compilation**: Optimized native binary for better performance

## System Requirements

### Minimum Requirements
- **Operating System**: Windows 10+, macOS 10.14+, Linux (kernel 3.10+)
- **Java**: JDK 21 or higher
- **Maven**: 3.8.6 or higher
- **Memory**: 2GB RAM minimum, 4GB recommended
- **Storage**: 500MB free space for wallet data and blockchain metadata

### Recommended Requirements
- **Operating System**: Latest stable version of your preferred OS
- **Java**: Latest JDK 21 LTS
- **Memory**: 8GB RAM or more
- **Storage**: SSD with 1GB+ free space
- **Network**: Stable internet connection

## Installation

### Prerequisites
1. Install JDK 21:
   ```bash
   # Ubuntu/Debian
   sudo apt update && sudo apt install openjdk-21-jdk
   
   # macOS (using Homebrew)
   brew install openjdk@21
   
   # Windows: Download from Oracle or Adoptium
   ```

2. Install Maven 3.8.6+:
   ```bash
   # Ubuntu/Debian
   sudo apt install maven
   
   # macOS (using Homebrew)
   brew install maven
   
   # Verify installation
   java -version
   mvn -version
   ```

### Building from Source

1. **Clone the repository**:
   ```bash
   git clone https://github.com/blocknetdx/xlite-daemon
   cd xlite-daemon
   ```

2. **Make Maven wrapper executable** (Linux/macOS only):
   ```bash
   chmod +x mvnw
   ```

3. **Build the project**:
   ```bash
   # Full build with native compilation (recommended)
   ./mvnw clean package -Pnative
   
   # Faster build without tests (development)
   ./mvnw clean package -Pnative-fast
   ```

4. **Run the application**:
   ```bash
   # Using the native binary
   ./target/xlite-daemon
   
   # Or using Maven
   ./mvnw exec:java
   ```

### Environment Variables

Set these environment variables before running:

```bash
# Wallet credentials: either one unlocks the wallet without the console menu.
export WALLET_MNEMONIC="your twelve word mnemonic phrase here"
export WALLET_PASSWORD="your secure password"

# Provider: set at most one. With none set, the daemon uses its built-in
# default (https://xliterevp.mywire.org/).
export EXR_ENDPOINT="http://exrproxy1.example.org:42114"
# export BASE_URL="https://adapter.example.org"
# export ELECTRUM_ENDPOINT="ws://127.0.0.1:5000"   # single-indexer test override
```

`EXR_ENDPOINT` is one endpoint: a comma-separated value keeps only the first
entry, and setting two provider variables is a startup error.

## Configuration

### Configuration Files Location

The daemon stores configuration files in your system's application data directory:

- **Windows**: `%appdata%\xlite-daemon\settings\config-*.json`
- **macOS**: `~/Library/Application Support/xlite-daemon/settings/config-*.json`
- **Linux**: `~/.config/xlite-daemon/settings/config-*.json`

Set `XLITE_DATA_HOME` to override the platform directory above. The
daemon aborts at startup when no override is set and the platform
default is missing (Windows with unset `%AppData%`, or an empty
`user.home`).

### Configuration Structure

Each cryptocurrency has its own configuration file named `config-{ticker}.json`:

```json
{
    "feeperbyte": 100,
    "mintxfee": 10000,
    "rpcEnabled": true,
    "rpcUsername": "xlite",
    "rpcPassword": "securepassword123",
    "rpcPort": 41419,
    "rpcBindAddress": "127.0.0.1",
    "addressCount": 25
}
```

Fee values come from the coin's blockchain-configuration-files entry.

### Configuration Options

| Setting | Type | Description | Default |
|---------|------|-------------|---------|
| `feeperbyte` | number | Fee per byte, in satoshis | coin configuration |
| `mintxfee` | number | Minimum transaction fee, in satoshis | coin configuration |
| `rpcEnabled` | boolean | Enable the coin's JSON-RPC listener | `false` on first run |
| `rpcUsername` | string | Basic-auth username | `""` |
| `rpcPassword` | string | Basic-auth password | `""` |
| `rpcPort` | number | Listener port; steps to the next free port when held | `-1000` (derive) |
| `rpcBindAddress` | string | Interface to bind | loopback |
| `addressCount` | number | Addresses to generate | `0` |

## Usage

### Starting the Daemon

1. **Basic startup**:
   ```bash
   ./target/xlite-daemon
   ```

2. **With custom arguments**:
   ```bash
   # Pick a provider explicitly
   ./target/xlite-daemon --exr-endpoint http://exrproxy1.example.org:42114

   # Write or refresh every coin's RPC configuration, then exit
   ./target/xlite-daemon --enablerpcandconfigure
   ```

3. **As a background service**:
   ```bash
   # Linux/macOS
   nohup ./target/xlite-daemon > daemon.log 2>&1 &
   
   # Windows
   start /B xlite-daemon.exe > daemon.log
   ```

### Wallet Management

#### Creating a New Wallet

1. **Create the wallet**:
   ```bash
   # Non-interactively, with the password from WALLET_PASSWORD
   ./target/xlite-daemon --createdefaultwallet

   # Or restore an existing phrase
   ./target/xlite-daemon --createwalletmnemonic
   ```
   With no wallet, the console menu creates one (1) or imports a phrase (3).

2. **Set a secure password**:
   ```bash
   export WALLET_PASSWORD="your-secure-password-here"
   ```

3. **Password strength**:
   The daemon scores the password and refuses anything below 9 points:
   - a password shorter than 8 characters scores 0;
   - length (8-9 vs 10+) is worth 1 or 2 points;
   - digits, lowercase, uppercase and special characters add 2 points each.

#### Importing an Existing Wallet

```bash
# Set your existing mnemonic
export WALLET_MNEMONIC="your existing twelve word phrase here"
export WALLET_PASSWORD="your password"

# Start the daemon
./target/xlite-daemon
```


### Supported Operations

#### Basic Wallet Operations

Every coin has its own listener and its own credentials. Both live in
`settings/config-<ticker>.json`; the examples use BLOCK on port 41419.

```bash
# Get wallet info for one coin
curl -s -u "$RPC_USER:$RPC_PASSWORD" \
  -X POST http://127.0.0.1:41419 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getinfo","params":[],"id":1}'

# Generate a new address
curl -s -u "$RPC_USER:$RPC_PASSWORD" \
  -X POST http://127.0.0.1:41419 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getnewaddress","params":[],"id":1}'

# List unspent outputs
curl -s -u "$RPC_USER:$RPC_PASSWORD" \
  -X POST http://127.0.0.1:41419 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"listunspent","params":[],"id":1}'

# The master endpoint lists the configured coins; it does not serve getinfo.
curl -s -u "$MASTER_USER:$MASTER_PASSWORD" \
  -X POST http://127.0.0.1:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getCoins","params":[],"id":1}'
```

#### Transaction Operations

```bash
# Create raw transaction
curl -s -u "$RPC_USER:$RPC_PASSWORD" \
  -X POST http://127.0.0.1:41419 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"createrawtransaction","params":[[{"txid":"...","vout":0}],{"address":0.001}],"id":1}'

# Sign transaction
curl -s -u "$RPC_USER:$RPC_PASSWORD" \
  -X POST http://127.0.0.1:41419 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"signrawtransaction","params":["rawtx"],"id":1}'

# Broadcast transaction
curl -s -u "$RPC_USER:$RPC_PASSWORD" \
  -X POST http://127.0.0.1:41419 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"sendrawtransaction","params":["signedtx"],"id":1}'

# Or build, sign and broadcast in one locked step; the third argument is a dry run.
curl -s -u "$RPC_USER:$RPC_PASSWORD" \
  -X POST http://127.0.0.1:41419 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"sendtransaction","params":["address",0.001,true],"id":1}'
```

## Supported Cryptocurrencies

The daemon supports the following coins:

| Coin | Ticker |
|------|--------|
| Blocknet | BLOCK |
| Bitcoin | BTC |
| Bitcoin Cash | BCH |
| BitCore | BTX |
| Litecoin | LTC |
| Dash | DASH |
| DigiByte | DGB |
| Dogecoin | DOGE |
| Syscoin | SYS |
| PIVX | PIVX |
| Ravencoin | RVN |
| Unobtanium | UNO |
| PKoin | PKOIN |
| Verge | XVG |

This is the wallet enum. Chain parameters — including each coin's default port
— come from blockchain-configuration-files at runtime, and the manifest decides
which of these coins a deployment actually serves.

### Network Configuration

Each coin has specific network parameters:
- **Mainnet**: Production blockchain networks
- **Testnet**: Testing and development networks
- **RPC Ports**: Default ports for JSON-RPC communication

## Security Features

### Wallet Encryption

The daemon uses industry-standard security practices:

1. **PBKDF2 Key Derivation**: SHA-256 with 100,000 iterations
2. **AES Encryption**: 256-bit encryption for wallet data
3. **Secure Random Generation**: Cryptographically strong random number generation
4. **Memory Protection**: Sensitive data cleared from memory after use

### Legacy Wallet Migration

The daemon automatically migrates legacy wallets:

1. **Detection**: Identifies SHA-1 based legacy wallets
2. **Backup**: Creates backup before migration
3. **Upgrade**: Migrates to SHA-256 with improved security
4. **Validation**: Verifies migration success before cleanup

### Password Security

The daemon includes password strength validation:

```bash
# Password scoring system:
# 8-9 characters: 1 point
# 10+ characters: 2 points
# Contains digit: +2 points
# Contains lowercase: +2 points
# Contains uppercase: +2 points
# Contains special character: +2 points
# Maximum score: 10 points
```

### Address Security

1. **HD Wallet Support**: Hierarchical Deterministic wallet generation
2. **Address Gap Limit**: Prevents address exhaustion attacks
3. **Forward Address Generation**: Pre-generates addresses for performance
4. **Address Discovery**: Automatically finds used addresses for recovery

## Troubleshooting

### Common Issues

#### 1. Port Already in Use

**Problem**: "Address already in use" error on startup

**Solution**:
```bash
# Check which process is using the port
lsof -i :9955  # Linux/macOS
netstat -ano | findstr :9955  # Windows

# Kill the process or change the port in config
```

#### 2. Wallet Not Found

**Problem**: "Wallet not found on disk" error

**Solution**:
```bash
# Check if wallet file exists
ls ~/.config/xlite-daemon/key.dat

# Verify permissions
chmod 600 ~/.config/xlite-daemon/key.dat
```

#### 3. Network Connection Issues

**Problem**: Cannot connect to blockchain networks

**Solution**:
```bash
# Check network connectivity
ping blockexplorer.com

# Verify firewall settings
# Ensure outbound connections are allowed
```

#### 4. Memory Issues

**Problem**: `OutOfMemoryError` (JAR build only; the native image has no Java heap)

**Solution**:
```bash
# JAR build: give the JVM a larger heap
java -Xmx2g -jar target/xlite-daemon-*.jar
```

### Log Analysis

#### Log File Locations

- **Error logs**: `~/.config/xlite-daemon/error-YYYY-MM-DD.log`
- **Application logs**: Console output (configurable)

#### Common Log Patterns

```bash
# Wallet initialization
[wallet] Initializing wallet for BTC

# Network connection
[peer] Connecting to network...

# RPC requests
[rpc] Received JSON-RPC request: getinfo

# Address discovery
[discovery] Processing batch starting at index 100
```

### Debug Mode

Enable debug logging with the variable the daemon reads:

```bash
export XLITE_DAEMON_LOG_LEVEL=FINE   # SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST
```

Diagnostics go to stderr, payloads to stdout. Logging is configured in code;
there is no `logging.properties`.

### Performance Optimization

#### Address Discovery

Discovery is automatic and unconfigurable: batches until an empty one, three
consecutive failures abort. No gap-limit or batch-size setting.

#### Memory

The packaged binary is a GraalVM native image, so there is no heap or GC flag
to tune. For the JAR path, pass JVM options through `MAVEN_OPTS` when using
`./mvnw exec:java`.

### Recovery Procedures

#### Wallet Recovery

1. **From Mnemonic**:
   ```bash
   export WALLET_MNEMONIC="your twelve word phrase"
   ./target/xlite-daemon
   ```

2. **From Backup**:
   ```bash
   # Restore from backup directory
   cp ~/.config/xlite-daemon/backups/key-backup-*.dat ~/.config/xlite-daemon/key.dat
   ```

#### Configuration Recovery

1. **Reset Configuration**:
   ```bash
   # Remove config files to reset
   rm ~/.config/xlite-daemon/settings/config-*.json
   ```

2. **Rebuild from Source**:
   ```bash
   ./mvnw clean package -Pnative
   ```

## API Reference

### JSON-RPC API

The daemon provides a comprehensive JSON-RPC API for wallet and blockchain operations.

#### Authentication

Every coin endpoint requires HTTP Basic auth with the credentials in that
coin's `settings/config-<ticker>.json`; the master endpoint uses
`config-master.json`. The examples below target one coin's listener, so the
URL and the credentials are set once:

```bash
export RPC_URL="http://127.0.0.1:41419"   # this coin's rpcPort
export RPC_AUTH="xlite:yourpassword"      # rpcUsername:rpcPassword
```

The master endpoint is a different port (default 9955) and serves only the
master methods — `getinfo` is not one of them.

#### Blockchain Operations

##### getblockchaininfo
Get blockchain information

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getblockchaininfo","params":[],"id":1}'
```

**Response**:
```json
{
  "result": {
    "chain": "main",
    "blocks": 700000,
    "headers": 700000,
    "bestblockhash": "00000000000000000007bd1b11320e37172c4467554f7a87bf769874e65e361d",
    "difficulty": 21768156514421.46,
    "mediantime": 1640995200
  }
}
```

##### getblockhash
Get block hash by height

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getblockhash","params":[700000],"id":1}'
```

##### getblock
Get block information

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getblock","params":["blockhash"],"id":1}'
```

#### Wallet Operations

##### getinfo
Get wallet information

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getinfo","params":[],"id":1}'
```

**Response**:
```json
{
  "result": {
    "version": "0.5.15",
    "protocolversion": 70015,
    "walletversion": 169900,
    "balance": 1.50000000,
    "blocks": 700000,
    "timeoffset": 0,
    "connections": 8,
    "proxy": "",
    "difficulty": 21768156514421.46,
    "testnet": false,
    "keypoololdest": 1640995200,
    "keypoolsize": 1000,
    "paytxfee": 0.00001000,
    "relayfee": 0.00001000,
    "errors": ""
  }
}
```

##### getnewaddress
Generate new wallet address

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getnewaddress","params":[],"id":1}'
```

**Response**:
```json
{
  "result": "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
}
```

##### listunspent
List unspent transaction outputs

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"listunspent","params":[],"id":1}'
```

**Response**:
```json
{
  "result": [
    {
      "txid": "a1b2c3d4...",
      "vout": 0,
      "address": "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
      "scriptPubKey": "001472749d6b6e...",
      "amount": 0.50000000,
      "confirmations": 100,
      "spendable": true
    }
  ]
}
```

##### gettransaction
Get transaction details

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"gettransaction","params":["txid"],"id":1}'
```

#### Raw Transaction Operations

##### createrawtransaction
Create raw transaction

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"createrawtransaction","params":[[{"txid":"a1b2c3d4...","vout":0}],[{"bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh":0.1}]],"id":1}'
```

##### decoderawtransaction
Decode raw transaction

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"decoderawtransaction","params":["rawtx"],"id":1}'
```

##### signrawtransaction
Sign raw transaction

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"signrawtransaction","params":["rawtx"],"id":1}'
```

##### sendrawtransaction
Broadcast transaction

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"sendrawtransaction","params":["signedtx"],"id":1}'
```

#### Utility Operations

##### dumpprivkey
Export private key

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"dumpprivkey","params":["address"],"id":1}'
```

##### importprivkey
Import private key

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"importprivkey","params":["privatekey"],"id":1}'
```

##### signmessage
Sign message with address

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"signmessage","params":["address","message"],"id":1}'
```

##### verifymessage
Verify signed message

```bash
curl -s -u "$RPC_AUTH" "$RPC_URL" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"verifymessage","params":["address","signature","message"],"id":1}'
```

### Error Handling

#### Common Error Codes

| Code | Meaning |
|------|---------|
| `-1` | Invalid arguments; the message carries the usage line |
| `-1001` | The body is not a JSON-RPC envelope |
| `-1112` | The provider session is not connected (HTTP 503) |
| `-32601` | Unknown method |
| `3002` | Wallet data is not ready yet; retry the call |
| `3003` | The indexer refuses an address the wallet owns; `historystatus` names it |
| provider code | A failed chain read or broadcast is reported with the provider's own code |

#### Error Response Format

```json
{
  "error": {
    "code": -1001,
    "message": "Error parsing JSON."
  },
  "result": null,
  "id": 1
}
```

## Advanced Configuration

### Custom RPC Ports

Configure custom RPC ports for each coin:

```json
{
  "rpcPort": 41419,
  "rpcEnabled": true,
  "rpcUsername": "xlite",
  "rpcPassword": "securepassword"
}
```

Each coin needs its own port; master defaults to 9955 in `config-master.json`.
A held port steps to the next free one and is written back to the file.

### Network Configuration

#### Provider Selection

No full node and no `customNodes`: one provider, chosen by `EXR_ENDPOINT`,
`BASE_URL` or `ELECTRUM_ENDPOINT`. With none set, the built-in default is used.

#### Proxy Settings

Proxy environment variables (`http_proxy`/`https_proxy`) are honoured by the
underlying HTTP client for the provider connection.

### Security Hardening

#### Firewall Configuration

Keep the listeners on loopback. Every coin binds `127.0.0.1` (or `::1`) by
default and the master endpoint does too, so no inbound rule is needed. Only
if an operator sets a non-loopback `rpcBindAddress` should the port be opened,
and then only to the hosts that need it:

```bash
# Linux (iptables), restricted to one trusted subnet
sudo iptables -A INPUT -p tcp -s 10.0.0.0/24 --dport 41419 -j ACCEPT
```

#### File Permissions

Secure wallet and configuration files:

```bash
# Set restrictive permissions
chmod 600 ~/.config/xlite-daemon/key.dat
chmod 600 ~/.config/xlite-daemon/settings/config-*.json
chmod 700 ~/.config/xlite-daemon/
```

### Monitoring and Logging

#### Log Rotation

Configure automatic log rotation:

```bash
# Create logrotate configuration
sudo nano /etc/logrotate.d/xlite-daemon

# Add configuration
/home/user/.config/xlite-daemon/error-*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
}
```

#### Health Monitoring

Monitor daemon health:

```bash
# Check process status
ps aux | grep xlite-daemon

# Monitor logs in real-time
tail -f ~/.config/xlite-daemon/error-*.log

# Check network connections
netstat -tulpn | grep xlite-daemon
```

### Backup and Recovery

#### Automated Backups

Create automated backup script:

```bash
#!/bin/bash
# backup-wallet.sh

BACKUP_DIR="/backup/xlite-daemon"
DATE=$(date +%Y%m%d_%H%M%S)

mkdir -p "$BACKUP_DIR"

# Backup wallet file
cp ~/.config/xlite-daemon/key.dat "$BACKUP_DIR/key-$DATE.dat"

# Backup configuration
cp ~/.config/xlite-daemon/settings/config-*.json "$BACKUP_DIR/"

# Compress backup
tar -czf "$BACKUP_DIR/backup-$DATE.tar.gz" -C "$BACKUP_DIR" .

# Clean old backups (keep 30 days)
find "$BACKUP_DIR" -name "*.tar.gz" -mtime +30 -delete

echo "Backup completed: $BACKUP_DIR/backup-$DATE.tar.gz"
```

#### Recovery Script

Create recovery script:

```bash
#!/bin/bash
# restore-wallet.sh

BACKUP_FILE="$1"

if [ -z "$BACKUP_FILE" ]; then
    echo "Usage: $0 <backup-file.tar.gz>"
    exit 1
fi

# Stop daemon
pkill xlite-daemon

# Backup current data
mv ~/.config/xlite-daemon ~/.config/xlite-daemon.backup

# Extract backup
tar -xzf "$BACKUP_FILE" -C ~/.config/

echo "Wallet restored from $BACKUP_FILE"
echo "Start the daemon to verify recovery"
```

This comprehensive user guide provides everything needed to install, configure, and use the XLite Daemon effectively. For additional support, refer to the troubleshooting section or consult the API reference for detailed technical information.