# cloudchains-p2p-java

This is the new headless edition, featuring a backend and a frontend in two separate executables.

For the frontend, see `cloudchains-p2p-ui`.

The old edition has discontinued and non-portable dependencies and is therefore no longer supported.

## Usage

### Compilation

Compilation of `cloudchains-p2p-java` is managed by the Gradle build system.

Gradle will handle all dependencies, compilation, and final packaging.

#### Compilation Requirements

* Gradle Dependencies
    * BitcoinJ Core 0.14.7
    * Bouncy Castle Provider 1.62
    * JSON 20180813
    * Netty 4.1.38.Final
    * Apache HttpClient 4.5.9
    * Google Gson 2.8.5
    * GraalVM 19.2.0
* Compilation Dependencies
    * Internet connection able to access Gradle, GitHub, and Maven Central
    * JDK 8
* Code Retrieval Tools
    * Git

#### Windows Build Dependencies
* [Chocolatey](https://chocolatey.org/)
* [Python 2.7](https://www.python.org/downloads/release)
* Windows SDK for Windows 7(Install via Chocolatey) 
    * (All builds must be performed from the Windows SDK 7.1 Command Prompt)
    * `choco install windows-sdk-7.1 kb2519277`
    * Afterwards, in cmd.exe `call "C:\Program Files\Microsoft SDKs\Windows\v7.1\Bin\SetEnv.cmd"`

#### Git Repository Cloning Procedure

```
$ git clone <https/ssh url>
$ cd cloudchains-p2p-java
```
    
#### Compilation Procedure

```
$ ./gradlew nativeImage
```

#### Installation Procedure (Linux) (Obsolete)

Currently, there is only a supported installation script for Linux. It will install the run script in the `/usr/local/bin` directory.

```
$ sudo ./install
```

### Runtime Requirements (Obsolete)

Because the needed libraries are packed inside the JAR executable, there is no need for having these libraries installed on a system that will only be running `cloudchains-p2p-java`.

* JRE 8

While not technically required, the `cloudchains-p2p-ui` frontend is recommended. The UI will securely connect to this backend. The backend only listens on the localhost (127.0.0.1) address and only accepts a single client for security.

### Execution

There are multiple ways to execute the JAR.

Any Platform with Gradle:

```
$ ./gradlew run
```

Any Platform with `java`:

```
$ java -jar build/libs/cloudchains-p2p-java.jar
```

Linux After Installation with Script:

```
$ cloudchains-p2p-java
```

Runtime Environment Variables:
```
WALLET_MNEMONIC
WALLET_PASSWORD
```

Supported RPC calls
```
Calls requiring XRouter calls: getblockhash, getblock, gettransaction, sendrawtransaction
help - This command help.
stop - Shutdown the server
=====Blockchain=====
gettxout <txid> <vout> - Get info about an unspent transaction output
=====Network=====
getinfo - Get information such as balances, protocol version, and more
getnetworkinfo - Get network information
getrawmempool - Get raw mempool
getblockchaininfo - Get blockchain info
getblockhash <height> - Get the hash of a block at a given height
getblock <hash> - Get a block's JSON representation given its hash
=====Wallet=====
listunspent - Get all UTXOs in the wallet
getnewaddress - Generate a new address
gettransaction <txid> - Get a transaction given its TXID
getaddressesbyaccount <account> - Get addresses belonging to a given account. The only account available is 'main' which contains all addresses
importprivkey <privkey> - Import an address given it's privkey
dumpprivkey <address> - Dump an addresses private key
=====Utilities=====
signmessage <address> <message> - Sign a message with a given address' private key
verifymessage <address> <signature> <message> - Verify a signature for a message signed by a given address
=====Raw Transactions=====
createrawtransaction <inputs> <outputs> - Create a raw transaction given inputs and outputs in JSON format. For more info, run createrawtransaction with no arguments.
decoderawtransaction <rawtx> - Get a raw transaction's JSON representation
signrawtransaction <rawtx> - Sign a raw transaction
sendrawtransaction <rawtx> - Broadcast a signed raw transaction to the network
```
