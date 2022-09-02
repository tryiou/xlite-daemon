#!/bin/bash

GEN_ADDRESS=$(curl -H "Content-Type: application/json" -d '{"method": "getnewaddress", "params": []}' http://user:pass@127.0.0.1:29332/ | jq --raw-output '.result')
echo Generated address: ${GEN_ADDRESS}

DUMPED_KEY=$(curl -H "Content-Type: application/json" -d '{"method": "dumpprivkey", "params": ['"${GEN_ADDRESS}"']}' http://user:pass@127.0.0.1:29332/ | jq --raw-output '.result')
echo Dumped private key: ${DUMPED_KEY}
