package io.cloudchains.app.util;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AtomicDouble;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.DumpedPrivateKey;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AddressBalance {
    private Address address;
    private DumpedPrivateKey privateKey;
    private AtomicReference<String> addrProp = null;
    private AtomicDouble balanceProp = null;
    private final CopyOnWriteArrayList<UTXO> utxos = new CopyOnWriteArrayList<>();

    public AddressBalance(Address address, DumpedPrivateKey privateKey) {
        this.address = address;
        this.privateKey = privateKey;
        setAddrProp(address.toBase58());
    }

    public Address getAddress() {
        return address;
    }

    public String getAddrProp() {
        return addrProperty().get();
    }

    private void setAddrProp(String value) {
        addrProperty().set(value);
    }

    private AtomicReference<String> addrProperty() {
        if (addrProp == null)
            addrProp = new AtomicReference<String>("addrProp");
        return addrProp;
    }

    public double getBalanceProp() {
        return balanceProperty().get();
    }

    private void setBalanceProp(double value) {
        balanceProperty().set(value);
    }

    public AtomicDouble balanceProperty() {
        if (balanceProp == null)
            balanceProp = new AtomicDouble(0);
        return balanceProp;
    }

    public DumpedPrivateKey getPrivateKey() {
        return privateKey;
    }

    public void clearUtxos() {
        synchronized (this) {
            utxos.removeIf(utxo -> !utxo.isSpent());
        }
    }

    public boolean addUtxo(UTXO utxo) {
        Preconditions.checkNotNull(utxo);
        synchronized (this) {
            // Only add UTXO's that do not exist in our wallet
            UTXO bUtxo = getUtxo(utxo.getTxid(), utxo.getVout());
            if (bUtxo == null)
                this.utxos.add(utxo);
            else
                return false;

            calculateBalance();
            return true;
        }
    }

    public void setUtxos(List<UTXO> recvUtxos) {
        synchronized (this) {
            List<UTXO> newUtxos = new ArrayList<>();

            if (this.utxos.size() > 0) {
                for (UTXO utxo : recvUtxos) {
                    for (UTXO bUtxo : this.utxos) {
                        if (!utxo.getTxid().equals(bUtxo.getTxid()) || utxo.getVout() != bUtxo.getVout()) {
                            newUtxos.add(utxo);
                        }
                    }
                }

                if (newUtxos.size() > 0) {
                    this.utxos.clear();
                    this.utxos.addAll(newUtxos);
                }
            } else {
                this.utxos.clear();
                this.utxos.addAll(recvUtxos);
            }

            calculateBalance();
        }
    }

    private UTXO getUtxo(String txid, int vout) {
        return utxos.stream().filter(o -> o.getTxid().equals(txid) && o.getVout() == vout).findFirst().orElse(null);
    }

    public List<UTXO> getSpentUtxos() {
        return utxos.stream().filter(UTXO::isSpent).collect(Collectors.toList());
    }

    public List<UTXO> getUtxos() {
        return utxos.stream().filter(utxo -> !utxo.isSpent()).collect(Collectors.toList());
    }

    public void calculateBalance() {
        Preconditions.checkNotNull(utxos);
        double balance = 0;

        for (UTXO utxo : utxos) {
            if (!utxo.isSpent()) {
                balance += utxo.getValue();
            }
        }

        balance /= 100000000.0;
        setBalanceProp(balance);
    }

}
