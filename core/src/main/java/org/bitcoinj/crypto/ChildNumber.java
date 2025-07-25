/*
 * Copyright 2013 Matija Mazi.
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

package org.bitcoinj.crypto;

import java.util.Locale;

/**
 * This is just a wrapper for the i (child number) as per BIP 32 with a boolean getter for the most significant bit
 * and a getter for the actual 0-based child number. A list of these forms a <i>path</i> through a
 * {@link DeterministicHierarchy}. This class is immutable.
 * <p>
 * This is a value-based class; use of identity-sensitive operations (including reference equality (==), identity
 * hash code, or synchronization) on instances of {@code ChildNumber} may have unpredictable results and should be avoided.
 */
public class ChildNumber implements Comparable<ChildNumber> {
    /**
     * The bit that's set in the child number to indicate whether this key is "hardened". Given a hardened key, it is
     * not possible to derive a child public key if you know only the hardened public key. With a non-hardened key this
     * is possible, so you can derive trees of public keys given only a public parent, but the downside is that it's
     * possible to leak private keys if you disclose a parent public key and a child private key (elliptic curve maths
     * allows you to work upwards).
     */
    public static final int HARDENED_BIT = 0x80000000;

    public static final ChildNumber ZERO = new ChildNumber(0);
    public static final ChildNumber ZERO_HARDENED = new ChildNumber(0, true);
    public static final ChildNumber ONE = new ChildNumber(1);
    public static final ChildNumber ONE_HARDENED = new ChildNumber(1, true);

    // See BIP-43, Purpose Field for Deterministic Wallets: https://github.com/bitcoin/bips/blob/master/bip-0043.mediawiki
    public static final ChildNumber PURPOSE_BIP44 = new ChildNumber(44, true);  // P2PKH
    public static final ChildNumber PURPOSE_BIP49 = new ChildNumber(49, true);  // P2WPKH-nested-in-P2SH
    public static final ChildNumber PURPOSE_BIP84 = new ChildNumber(84, true);  // P2WPKH
    public static final ChildNumber PURPOSE_BIP86 = new ChildNumber(86, true);  // P2TR

    public static final ChildNumber COINTYPE_BTC = new ChildNumber(0, true);    // MainNet
    public static final ChildNumber COINTYPE_TBTC = new ChildNumber(1, true);   // TestNet

    public static final ChildNumber CHANGE_RECEIVING = new ChildNumber(0, false);
    public static final ChildNumber CHANGE_CHANGE = new ChildNumber(1, false);

    /** Integer i as per BIP 32 spec, including the MSB denoting derivation type (0 = public, 1 = private) **/
    private final int i;

    public ChildNumber(int childNumber, boolean isHardened) {
        if (hasHardenedBit(childNumber))
            throw new IllegalArgumentException("Most significant bit is reserved and shouldn't be set: " + childNumber);
        i = isHardened ? (childNumber | HARDENED_BIT) : childNumber;
    }

    public ChildNumber(int i) {
        this.i = i;
    }

    /**
     * Parse a single child number.
     *
     * @param str string of the form "1" or "1H"
     * @return child number instance
     * @throws NumberFormatException when parsing fails
     */
    public static ChildNumber parse(String str) {
        boolean isHard = str.endsWith("H");
        String nodeNumber = isHard ?
                str.substring(0, str.length() - 1) :
                str;
        return new ChildNumber(Integer.parseInt(nodeNumber.trim()), isHard);
    }

    /** Returns the uint32 encoded form of the path element, including the most significant bit. */
    public int getI() {
        return i;
    }

    /** Returns the uint32 encoded form of the path element, including the most significant bit. */
    public int i() { return i; }

    public boolean isHardened() {
        return hasHardenedBit(i);
    }

    private static boolean hasHardenedBit(int a) {
        return (a & HARDENED_BIT) != 0;
    }

    /** Returns the child number without the hardening bit set (i.e. index in that part of the tree). */
    public int num() {
        return i & (~HARDENED_BIT);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%d%s", num(), isHardened() ? "H" : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return i == ((ChildNumber)o).i;
    }

    @Override
    public int hashCode() {
        return i;
    }

    @Override
    public int compareTo(ChildNumber other) {
        // note that in this implementation compareTo() is not consistent with equals()
        return Integer.compare(this.num(), other.num());
    }
}
