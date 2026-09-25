package io.xlite.daemon.app.util;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AtomicDouble;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.LegacyAddress;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AddressBalance {
    private LegacyAddress address;
    private DumpedPrivateKey privateKey;
    private final AtomicReference<String> addrProp;
    private final AtomicDouble balanceProp;
    private final CopyOnWriteArrayList<UTXO> utxos = new CopyOnWriteArrayList<>();

    public AddressBalance(LegacyAddress address, DumpedPrivateKey privateKey) {
        this.address = address;
        this.privateKey = privateKey;
        this.addrProp = new AtomicReference<>(address.toBase58());
        this.balanceProp = new AtomicDouble(0);
    }

    public LegacyAddress getAddress() {
        return address;
    }

    public String getAddrProp() {
        return addrProperty().get();
    }

    private void setAddrProp(String value) {
        addrProperty().set(value);
    }

    private AtomicReference<String> addrProperty() {
        return addrProp;
    }

    public double getBalanceProp() {
        return balanceProperty().get();
    }

    private void setBalanceProp(double value) {
        balanceProperty().set(value);
    }

    public AtomicDouble balanceProperty() {
        return balanceProp;
    }

    public DumpedPrivateKey getPrivateKey() {
        return privateKey;
    }

    public void clearPrivateKey() {
        privateKey = null;
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
