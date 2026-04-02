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
- **Multi-Currency Support**: Supports 10+ cryptocurrencies including Bitcoin, Litecoin, Dash, and more
- **Secure Wallet Management**: Industry-standard encryption with PBKDF2 key derivation
- **Address Discovery**: Automatic detection of used addresses for wallet recovery
- **JSON-RPC API**: Comprehensive API for wallet operations and blockchain queries
- **XRouter Integration**: Support for cross-chain communication (Blocknet network)
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
# Required for wallet initialization
export WALLET_MNEMONIC="your twelve word mnemonic phrase here"
export WALLET_PASSWORD="your secure password"

# Optional: Custom EXR endpoints (comma-separated list of backend servers)
export EXR_ENDPOINT="https://server1.example.com,https://server2.example.com"
```

## Configuration

### Configuration Files Location

The daemon stores configuration files in your system's application data directory:

- **Windows**: `%appdata%\CloudChains\settings\config-*.json`
- **macOS**: `~/Library/Application Support/CloudChains/settings/config-*.json`
- **Linux**: `~/.config/CloudChains/settings/config-*.json`

### Configuration Structure

Each cryptocurrency has its own configuration file named `config-{ticker}.json`:

```json
{
    "fee": 0.0001,
    "feeFlat": true,
    "rpcEnabled": true,
    "rpcUsername": "xlite",
    "rpcPassword": "securepassword123",
    "rpcPort": 9955,
    "addressCount": 25
}
```

### Configuration Options

| Setting | Type | Description | Default |
|---------|------|-------------|---------|
| `fee` | number | Transaction fee rate | 0.0001 |
| `feeFlat` | boolean | Use flat fee vs dynamic | true |
| `rpcEnabled` | boolean | Enable JSON-RPC server | false |
| `rpcUsername` | string | RPC authentication username | "" |
| `rpcPassword` | string | RPC authentication password | "" |
| `rpcPort` | number | RPC server port | -1000 (auto) |
| `addressCount` | number | Number of addresses to generate | 0 |

## Usage

### Starting the Daemon

1. **Basic startup**:
   ```bash
   ./target/xlite-daemon
   ```

2. **With custom arguments**:
   ```bash
   ./target/xlite-daemon --network=testnet --debug
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

1. **Generate a new mnemonic**:
   ```bash
   # The daemon will automatically generate a 12-word mnemonic on first run
   # or you can provide one via WALLET_MNEMONIC environment variable
   ```

2. **Set a secure password**:
   ```bash
   export WALLET_PASSWORD="your-secure-password-here"
   ```

3. **Verify password strength**:
   The daemon includes a password strength calculator that evaluates:
   - Length (8+ characters required)
   - Character variety (uppercase, lowercase, numbers, symbols)
   - Overall security score (0-10)

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

```bash
# Get wallet balance
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getinfo","params":[],"id":1}'

# Generate new address
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getnewaddress","params":[],"id":1}'

# List unspent transactions (UTXOs)
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"listunspent","params":[],"id":1}'
```

#### Transaction Operations

```bash
# Create raw transaction
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"createrawtransaction","params":[[{"txid":"...","vout":0}],{"address":0.001}],"id":1}'

# Sign transaction
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"signrawtransaction","params":["rawtx"],"id":1}'

# Broadcast transaction
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"sendrawtransaction","params":["signedtx"],"id":1}'
```

## Supported Cryptocurrencies

The daemon supports the following cryptocurrencies:

| Coin | Ticker | Network | RPC Port | Status |
|------|--------|---------|----------|---------|
| Blocknet | BLOCK | Mainnet | 41419 | ✅ Active |
| Blocknet Testnet | TBLOCK | Testnet | 41419 | ✅ Active |
| Bitcoin | BTC | Mainnet | 8332 | ✅ Active |
| Litecoin | LTC | Mainnet | 9332 | ✅ Active |
| Dash | DASH | Mainnet | 9998 | ✅ Active |
| Dogecoin | DOGE | Mainnet | 22555 | ✅ Active |
| Syscoin | SYS | Mainnet | 8370 | ✅ Active |
| PIVX | PIVX | Mainnet | 9951 | ✅ Active |
| Unobtanium | UNO | Mainnet | 65111 | ✅ Active |

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
ls ~/.config/CloudChains/key.dat

# Verify permissions
chmod 600 ~/.config/CloudChains/key.dat
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

**Problem**: OutOfMemoryError or slow performance

**Solution**:
```bash
# Increase Java heap size
export JAVA_OPTS="-Xmx2g -Xms1g"

# Or modify the startup script
java -Xmx2g -jar xlite-daemon.jar
```

### Log Analysis

#### Log File Locations

- **Error logs**: `~/.config/CloudChains/error-YYYY-MM-DD.log`
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

Enable debug logging:

```bash
# Set logging level
export LOG_LEVEL=DEBUG

# Or modify logging.properties
handlers=java.util.logging.ConsoleHandler
.level=FINE
```

### Performance Optimization

#### Address Discovery Optimization

1. **Batch Size**: Adjust batch size for your network
2. **Gap Limit**: Modify gap limit for faster discovery
3. **Timeout Settings**: Configure discovery timeouts

#### Memory Management

1. **Heap Size**: Allocate sufficient memory
2. **GC Tuning**: Optimize garbage collection
3. **Connection Pooling**: Reuse network connections

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
   cp ~/.config/CloudChains/backups/key-backup-*.dat ~/.config/CloudChains/key.dat
   ```

#### Configuration Recovery

1. **Reset Configuration**:
   ```bash
   # Remove config files to reset
   rm ~/.config/CloudChains/settings/config-*.json
   ```

2. **Rebuild from Source**:
   ```bash
   ./mvnw clean package -Pnative
   ```

## API Reference

### JSON-RPC API

The daemon provides a comprehensive JSON-RPC API for wallet and blockchain operations.

#### Authentication

All RPC requests require authentication:

```bash
# Set credentials in config file or environment
rpcUsername=xlite
rpcPassword=yourpassword
```

#### Blockchain Operations

##### getblockchaininfo
Get blockchain information

```bash
curl -X POST http://localhost:9955 \
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
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getblockhash","params":[700000],"id":1}'
```

##### getblock
Get block information

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getblock","params":["blockhash"],"id":1}'
```

#### Wallet Operations

##### getinfo
Get wallet information

```bash
curl -X POST http://localhost:9955 \
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
curl -X POST http://localhost:9955 \
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
curl -X POST http://localhost:9955 \
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
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"gettransaction","params":["txid"],"id":1}'
```

#### Raw Transaction Operations

##### createrawtransaction
Create raw transaction

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"createrawtransaction","params":[[{"txid":"a1b2c3d4...","vout":0}],[{"bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh":0.1}]],"id":1}'
```

##### decoderawtransaction
Decode raw transaction

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"decoderawtransaction","params":["rawtx"],"id":1}'
```

##### signrawtransaction
Sign raw transaction

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"signrawtransaction","params":["rawtx"],"id":1}'
```

##### sendrawtransaction
Broadcast transaction

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"sendrawtransaction","params":["signedtx"],"id":1}'
```

#### Utility Operations

##### dumpprivkey
Export private key

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"dumpprivkey","params":["address"],"id":1}'
```

##### importprivkey
Import private key

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"importprivkey","params":["privatekey"],"id":1}'
```

##### signmessage
Sign message with address

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"signmessage","params":["address","message"],"id":1}'
```

##### verifymessage
Verify signed message

```bash
curl -X POST http://localhost:9955 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"verifymessage","params":["address","signature","message"],"id":1}'
```

### Error Handling

#### Common Error Codes

| Code | Message | Description |
|------|---------|-------------|
| -1 | "Bad password" | Incorrect wallet password |
| -2 | "Unsupported coin" | Coin not supported |
| -3 | "Bad mnemonic" | Invalid mnemonic phrase |
| -4 | "Change password failed" | Password change failed |
| -5 | "Wallet not found" | No wallet file exists |

#### Error Response Format

```json
{
  "error": {
    "code": -1,
    "message": "Bad password"
  },
  "id": 1
}
```

## Advanced Configuration

### Custom RPC Ports

Configure custom RPC ports for each coin:

```json
{
  "rpcPort": 9955,
  "rpcEnabled": true,
  "rpcUsername": "xlite",
  "rpcPassword": "securepassword"
}
```

### Network Configuration

#### Proxy Settings

Configure proxy for network connections:

```bash
# Set proxy environment variables
export http_proxy="http://proxy.company.com:8080"
export https_proxy="http://proxy.company.com:8080"
```

#### Custom Nodes

Configure custom blockchain nodes:

```json
{
  "customNodes": [
    "node1.example.com:8333",
    "node2.example.com:8333"
  ]
}
```

### Performance Tuning

#### JVM Parameters

Optimize Java Virtual Machine settings:

```bash
# High-performance settings
export JAVA_OPTS="-Xmx4g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

#### Network Optimization

```bash
# Increase connection limits
export MAX_CONNECTIONS=50

# Optimize network buffers
export NETWORK_BUFFER_SIZE=65536
```

### Security Hardening

#### Firewall Configuration

Allow necessary ports:

```bash
# Linux (iptables)
sudo iptables -A INPUT -p tcp --dport 9955 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 41419 -j ACCEPT

# Windows (PowerShell)
New-NetFirewallRule -DisplayName "XLite Daemon" -Direction Inbound -Protocol TCP -LocalPort 9955 -Action Allow
```

#### File Permissions

Secure wallet and configuration files:

```bash
# Set restrictive permissions
chmod 600 ~/.config/CloudChains/key.dat
chmod 600 ~/.config/CloudChains/settings/config-*.json
chmod 700 ~/.config/CloudChains/
```

### Monitoring and Logging

#### Log Rotation

Configure automatic log rotation:

```bash
# Create logrotate configuration
sudo nano /etc/logrotate.d/xlite-daemon

# Add configuration
/home/user/.config/CloudChains/error-*.log {
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
tail -f ~/.config/CloudChains/error-*.log

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
cp ~/.config/CloudChains/key.dat "$BACKUP_DIR/key-$DATE.dat"

# Backup configuration
cp ~/.config/CloudChains/settings/config-*.json "$BACKUP_DIR/"

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
mv ~/.config/CloudChains ~/.config/CloudChains.backup

# Extract backup
tar -xzf "$BACKUP_FILE" -C ~/.config/

echo "Wallet restored from $BACKUP_FILE"
echo "Start the daemon to verify recovery"
```

This comprehensive user guide provides everything needed to install, configure, and use the XLite Daemon effectively. For additional support, refer to the troubleshooting section or consult the API reference for detailed technical information.