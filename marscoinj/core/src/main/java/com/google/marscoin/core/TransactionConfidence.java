/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.marscoin.core;

import com.google.common.base.Preconditions;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.ListIterator;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <p>A TransactionConfidence object tracks data you can use to make a confidence decision about a transaction.
 * It also contains some pre-canned rules for common scenarios: if you aren't really sure what level of confidence
 * you need, these should prove useful. You can get a confidence object using {@link Transaction#getConfidence()}.
 * They cannot be constructed directly.</p>
 *
 * <p>Confidence in a transaction can come in multiple ways:</p>
 *
 * <ul>
 * <li>Because you created it yourself and only you have the necessary keys.</li>
 * <li>Receiving it from a fully validating peer you know is trustworthy, for instance, because it's run by yourself.</li>
 * <li>Receiving it from a peer on the network you randomly chose. If your network connection is not being
 *     intercepted, you have a pretty good chance of connecting to a node that is following the rules.</li>
 * <li>Receiving it from multiple peers on the network. If your network connection is not being intercepted,
 *     hearing about a transaction from multiple peers indicates the network has accepted the transaction and
 *     thus miners likely have too (miners have the final say in whether a transaction becomes valid or not).</li>
 * <li>Seeing the transaction appear appear in a block on the main chain. Your confidence increases as the transaction
 *     becomes further buried under work. Work can be measured either in blocks (roughly, units of time), or
 *     amount of work done.</li>
 * </ul>
 *
 * <p>Alternatively, you may know that the transaction is "dead", that is, one or more of its inputs have
 * been double spent and will never confirm unless there is another re-org.</p>
 *
 * <p>TransactionConfidence is updated via the {@link com.google.marscoin.core.TransactionConfidence#notifyWorkDone(Block)}
 * method to ensure the block depth and work done are up to date.</p>
 * To make a copy that won't be changed, use {@link com.google.marscoin.core.TransactionConfidence#duplicate()}.
 */
public class TransactionConfidence implements Serializable {
    private static final long serialVersionUID = 4577920141400556444L;

    /**
     * The peers that have announced the transaction to us. Network nodes don't have stable identities, so we use
     * IP address as an approximation. It's obviously vulnerable to being gamed if we allow arbitrary people to connect
     * to us, so only peers we explicitly connected to should go here.
     */
    private CopyOnWriteArrayList<PeerAddress> broadcastBy;
    /** The Transaction that this confidence object is associated with. */
    private Transaction transaction;
    // Lazily created listeners array.
    private transient CopyOnWriteArrayList<Listener> listeners;

    // The depth of the transaction on the best chain in blocks. An unconfirmed block has depth 0.
    private int depth;
    // The cumulative work done for the blocks that bury this transaction.
    private BigInteger workDone = BigInteger.ZERO;

    /** Describes the state of the transaction in general terms. Properties can be read to learn specifics. */
    public enum ConfidenceType {
        /** If BUILDING, then the transaction is included in the best chain and your confidence in it is increasing. */
        BUILDING(1),

        /**
         * If NOT_SEEN_IN_CHAIN, then the transaction is pending and should be included shortly, as long as it is being
         * announced and is considered valid by the network. A pending transaction will be announced if the containing
         * wallet has been attached to a live {@link PeerGroup} using {@link PeerGroup#addWallet(Wallet)}.
         * You can estimate how likely the transaction is to be included by connecting to a bunch of nodes then measuring
         * how many announce it, using {@link com.google.marscoin.core.TransactionConfidence#numBroadcastPeers()}.
         * Or if you saw it from a trusted peer, you can assume it's valid and will get mined sooner or later as well.
         */
        NOT_SEEN_IN_CHAIN(2),

        /**
         * If NOT_IN_BEST_CHAIN, then the transaction has been included in a block, but that block is on a fork. A
         * transaction can change from BUILDING to NOT_IN_BEST_CHAIN and vice versa if a reorganization takes place,
         * due to a split in the consensus.
         */
        NOT_IN_BEST_CHAIN(3),

        /**
         * If DEAD, then it means the transaction won't confirm unless there is another re-org,
         * because some other transaction is spending one of its inputs. Such transactions should be alerted to the user
         * so they can take action, eg, suspending shipment of goods if they are a merchant.
         * It can also mean that a coinbase transaction has been made dead from it being moved onto a side chain.
         */
        DEAD(4),

        /**
         * If a transaction hasn't been broadcast yet, or there's no record of it, its confidence is UNKNOWN.
         */
        UNKNOWN(0);
        
        private int value;
        ConfidenceType(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }

        public static ConfidenceType valueOf(int value) {
            switch (value) {
            case 0: return UNKNOWN;
            case 1: return BUILDING;
            case 2: return NOT_SEEN_IN_CHAIN;
            case 3: return NOT_IN_BEST_CHAIN;
            case 4: return DEAD;
            default: return null;
            }
        }

    }

    private ConfidenceType confidenceType = ConfidenceType.UNKNOWN;
    private int appearedAtChainHeight = -1;
    // The transaction that double spent this one, if any.
    private Transaction overridingTransaction;

    /**
     * Information about where the transaction was first seen (network, sent direct from peer, created by ourselves).
     * Useful for risk analyzing pending transactions. Probably not that useful after a tx is included in the chain,
     * unless re-org double spends start happening frequently.
     */
    public enum Source {
        /** We don't know where the transaction came from. */
        UNKNOWN,
        /** We got this transaction from a network peer. */
        NETWORK,
        /** This transaction was created by our own wallet, so we know it's not a double spend. */
        SELF
    }
    private Source source = Source.UNKNOWN;

    public TransactionConfidence(Transaction tx) {
        // Assume a default number of peers for our set.
        broadcastBy = new CopyOnWriteArrayList<PeerAddress>();
        listeners = new CopyOnWriteArrayList<Listener>();
        transaction = tx;
    }

    /**
     * <p>A confidence listener is informed when the level of {@link TransactionConfidence} is updated by something, like
     * for example a {@link Wallet}. You can add listeners to update your user interface or manage your order tracking
     * system when confidence levels pass a certain threshold. <b>Note that confidence can go down as well as up.</b>
     * For example, this can happen if somebody is doing a double-spend attack against you. Whilst it's unlikely, your
     * code should be able to handle that in order to be correct.</p>
     *
     * <p>During listener execution, it's safe to remove the current listener but not others.</p>
     */
    public interface Listener {
        public void onConfidenceChanged(Transaction tx);
    }

    /**
     * <p>Adds an event listener that will be run when this confidence object is updated. The listener will be locked and
     * is likely to be invoked on a peer thread.</p>
     *
     * <p>Note that this is NOT called when every block arrives. Instead it is called when the transaction
     * transitions between confidence states, ie, from not being seen in the chain to being seen (not necessarily in
     * the best chain). If you want to know when the transaction gets buried under another block, implement a
     * {@link BlockChainListener}, attach it to a {@link BlockChain} and then use the getters on the
     * confidence object to determine the new depth.</p>
     */
    public void addEventListener(Listener listener) {
        Preconditions.checkNotNull(listener);
        listeners.addIfAbsent(listener);
    }

    public void removeEventListener(Listener listener) {
        Preconditions.checkNotNull(listener);
        listeners.remove(listener);
    }

    /**
     * Returns the chain height at which the transaction appeared if confidence type is BUILDING.
     * @throws IllegalStateException if the confidence type is not BUILDING.
     */
    public synchronized int getAppearedAtChainHeight() {
        if (getConfidenceType() != ConfidenceType.BUILDING)
            throw new IllegalStateException("Confidence type is " + getConfidenceType() + ", not BUILDING");
        return appearedAtChainHeight;
    }

    /**
     * The chain height at which the transaction appeared, if it has been seen in the best chain. Automatically sets
     * the current type to {@link ConfidenceType#BUILDING}.
     */
    public synchronized void setAppearedAtChainHeight(int appearedAtChainHeight) {
        if (appearedAtChainHeight < 0)
            throw new IllegalArgumentException("appearedAtChainHeight out of range");
        this.appearedAtChainHeight = appearedAtChainHeight;
        setConfidenceType(ConfidenceType.BUILDING);
    }

    /**
     * Returns a general statement of the level of confidence you can have in this transaction.
     */
    public synchronized ConfidenceType getConfidenceType() {
        return confidenceType;
    }

    /**
     * Called by other objects in the system, like a {@link Wallet}, when new information about the confidence of a 
     * transaction becomes available.
     */
    public void setConfidenceType(ConfidenceType confidenceType) {
        // Don't inform the event listeners if the confidence didn't really change.
        synchronized (this) {
            if (confidenceType == this.confidenceType)
                return;
            this.confidenceType = confidenceType;
        }
        runListeners();
    }


    /**
     * Called by a {@link Peer} when a transaction is pending and announced by a peer. The more peers announce the
     * transaction, the more peers have validated it (assuming your internet connection is not being intercepted).
     * If confidence is currently unknown, sets it to {@link ConfidenceType#NOT_SEEN_IN_CHAIN}. Listeners will be
     * invoked in this case.
     *
     * @param address IP address of the peer, used as a proxy for identity.
     */
    public void markBroadcastBy(PeerAddress address) {
        if (!broadcastBy.addIfAbsent(address))
            return;  // Duplicate.
        synchronized (this) {
            if (getConfidenceType() == ConfidenceType.UNKNOWN) {
                this.confidenceType = ConfidenceType.NOT_SEEN_IN_CHAIN;
            }
        }
        runListeners();
    }

    /**
     * Returns how many peers have been passed to {@link TransactionConfidence#markBroadcastBy}.
     */
    public int numBroadcastPeers() {
        return broadcastBy.size();
    }

    /**
     * Returns a snapshot of {@link PeerAddress}es that announced the transaction.
     */
    public ListIterator<PeerAddress> getBroadcastBy() {
        return broadcastBy.listIterator();
    }

    /** Returns true if the given address has been seen via markBroadcastBy() */
    public boolean wasBroadcastBy(PeerAddress address) {
        return broadcastBy.contains(address);
    }

    @Override
    public synchronized String toString() {
        StringBuilder builder = new StringBuilder();
        int peers = numBroadcastPeers();
        if (peers > 0) {
            builder.append("Seen by ");
            builder.append(peers);
            if (peers > 1)
                builder.append(" peers. ");
            else
                builder.append(" peer. ");
        }
        switch (getConfidenceType()) {
            case UNKNOWN:
                builder.append("Unknown confidence level.");
                break;
            case DEAD:
                builder.append("Dead: overridden by double spend and will not confirm.");
                break;
            case NOT_IN_BEST_CHAIN: 
                builder.append("Seen in side chain but not best chain.");
                break;
            case NOT_SEEN_IN_CHAIN:
                builder.append("Not seen in chain.");
                break;
            case BUILDING:
                builder.append(String.format("Appeared in best chain at height %d, depth %d, work done %s.",
                        getAppearedAtChainHeight(), getDepthInBlocks(), getWorkDone()));
                break;
        }
        return builder.toString();
    }

    /**
     * Called by the wallet when the tx appears on the best chain and a new block is added to the top.
     * Updates the internal counter that tracks how deeply buried the block is.
     * Work is the value of block.getWork().
     */
    public void notifyWorkDone(Block block) throws VerificationException {
        boolean notify = false;
        synchronized (this) {
            if (getConfidenceType() == ConfidenceType.BUILDING) {
                this.depth++;
                this.workDone = this.workDone.add(block.getWork());
                notify = true;
            }
        }
        if (notify)
            runListeners();
    }

    /**
     * Depth in the chain is an approximation of how much time has elapsed since the transaction has been confirmed. On
     * average there is supposed to be a new block every 10 minutes, but the actual rate may vary. The reference
     * (Satoshi) implementation considers a transaction impractical to reverse after 6 blocks, but as of EOY 2011 network
     * security is high enough that often only one block is considered enough even for high value transactions. For low
     * value transactions like songs, or other cheap items, no blocks at all may be necessary.<p>
     *     
     * If the transaction appears in the top block, the depth is one. If the transaction does not appear in the best
     * chain yet, throws IllegalStateException, so use {@link com.google.marscoin.core.TransactionConfidence#getConfidenceType()}
     * to check first.
     *
     * @throws IllegalStateException if confidence type != BUILDING.
     * @return depth
     */
    public synchronized int getDepthInBlocks() {
        if (getConfidenceType() != ConfidenceType.BUILDING) {
            throw new IllegalStateException("Confidence type is not BUILDING");
        }
        return depth;
    }

    /*
     * Set the depth in blocks. Having one block confirmation is a depth of one.
     */
    public synchronized void setDepthInBlocks(int depth) {
        this.depth = depth;
    }

    /**
     * Returns the estimated amount of work (number of hashes performed) on this transaction. Work done is a measure of
     * security that is related to depth in blocks, but more predictable: the network will always attempt to produce six
     * blocks per hour by adjusting the difficulty target. So to know how much real computation effort is needed to
     * reverse a transaction, counting blocks is not enough.
     *
     * @throws IllegalStateException if confidence type is not BUILDING
     * @return estimated number of hashes needed to reverse the transaction.
     */
    public synchronized BigInteger getWorkDone() {
        if (getConfidenceType() != ConfidenceType.BUILDING) {
            throw new IllegalStateException("Confidence type is not BUILDING");
        }
        return workDone;
    }

    public synchronized void setWorkDone(BigInteger workDone) {
        this.workDone = workDone;
    }

    /**
     * If this transaction has been overridden by a double spend (is dead), this call returns the overriding transaction.
     * Note that this call <b>can return null</b> if you have migrated an old wallet, as pre-Jan 2012 wallets did not
     * store this information.
     *
     * @return the transaction that double spent this one
     * @throws IllegalStateException if confidence type is not OVERRIDDEN_BY_DOUBLE_SPEND.
     */
    public synchronized Transaction getOverridingTransaction() {
        if (getConfidenceType() != ConfidenceType.DEAD)
            throw new IllegalStateException("Confidence type is " + getConfidenceType() +
                                            ", not OVERRIDDEN_BY_DOUBLE_SPEND");
        return overridingTransaction;
    }

    /**
     * Called when the transaction becomes newly dead, that is, we learn that one of its inputs has already been spent
     * in such a way that the double-spending transaction takes precedence over this one. It will not become valid now
     * unless there is a re-org. Automatically sets the confidence type to DEAD.
     */
    public synchronized void setOverridingTransaction(Transaction overridingTransaction) {
        this.overridingTransaction = overridingTransaction;
        setConfidenceType(ConfidenceType.DEAD);
    }

    /** Returns a copy of this object. Event listeners are not duplicated. */
    public synchronized TransactionConfidence duplicate() {
        TransactionConfidence c = new TransactionConfidence(transaction);
        // There is no point in this sync block, it's just to help FindBugs.
        synchronized (c) {
            c.broadcastBy.addAll(broadcastBy);
            c.confidenceType = confidenceType;
            c.overridingTransaction = overridingTransaction;
            c.appearedAtChainHeight = appearedAtChainHeight;
            return c;
        }
    }

    private void runListeners() {
        for (Listener listener : listeners)
            listener.onConfidenceChanged(transaction);
    }

    /**
     * The source of a transaction tries to identify where it came from originally. For instance, did we download it
     * from the peer to peer network, or make it ourselves, or receive it via Bluetooth, or import it from another app,
     * and so on. This information is useful for {@link Wallet.CoinSelector} implementations to risk analyze
     * transactions and decide when to spend them.
     */
    public synchronized Source getSource() {
        return source;
    }

    /**
     * The source of a transaction tries to identify where it came from originally. For instance, did we download it
     * from the peer to peer network, or make it ourselves, or receive it via Bluetooth, or import it from another app,
     * and so on. This information is useful for {@link Wallet.CoinSelector} implementations to risk analyze
     * transactions and decide when to spend them.
     */
    public synchronized void setSource(Source source) {
        this.source = source;
    }
}
