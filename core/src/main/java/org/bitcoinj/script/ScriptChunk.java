/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
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

package org.bitcoinj.script;

import org.bitcoinj.base.internal.ByteUtils;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

import static org.bitcoinj.base.internal.Preconditions.checkState;
import static org.bitcoinj.script.ScriptOpCodes.OP_0;
import static org.bitcoinj.script.ScriptOpCodes.OP_1;
import static org.bitcoinj.script.ScriptOpCodes.OP_16;
import static org.bitcoinj.script.ScriptOpCodes.OP_1NEGATE;
import static org.bitcoinj.script.ScriptOpCodes.OP_PUSHDATA1;
import static org.bitcoinj.script.ScriptOpCodes.OP_PUSHDATA2;
import static org.bitcoinj.script.ScriptOpCodes.OP_PUSHDATA4;
import static org.bitcoinj.script.ScriptOpCodes.getOpCodeName;
import static org.bitcoinj.script.ScriptOpCodes.getPushDataName;

/**
 * A script element that is either a data push (signature, pubkey, etc) or a non-push (logic, numeric, etc) operation.
 */
public class ScriptChunk {
    /** Operation to be executed. Opcodes are defined in {@link ScriptOpCodes}. */
    final int opcode;
    /**
     * For push operations, this is the vector to be pushed on the stack. For {@link ScriptOpCodes#OP_0}, the vector is
     * empty. Null for non-push operations.
     */
    @Nullable
    final byte[] data;

    public ScriptChunk(int opcode, @Nullable byte[] data) {
        this.opcode = opcode;
        this.data = data;
    }

    public boolean equalsOpCode(int opcode) {
        return opcode == this.opcode;
    }

    /**
     * If this chunk is a single byte of non-pushdata content (could be OP_RESERVED or some invalid Opcode)
     */
    public boolean isOpCode() {
        return opcode > OP_PUSHDATA4;
    }

    /**
     * Returns true if this chunk is pushdata content, including the single-byte pushdatas.
     */
    public boolean isPushData() {
        return opcode <= OP_16;
    }

    /** If this chunk is an OP_N opcode returns the equivalent integer value. */
    public int decodeOpN() {
        return Script.decodeFromOpN(opcode);
    }

    /**
     * Called on a pushdata chunk, returns true if it uses the smallest possible way (according to BIP62) to push the data.
     */
    public boolean isShortestPossiblePushData() {
        checkState(isPushData());
        if (data == null)
            return true;   // OP_N
        if (data.length == 0)
            return opcode == OP_0;
        if (data.length == 1) {
            byte b = data[0];
            if (b >= 0x01 && b <= 0x10)
                return opcode == OP_1 + b - 1;
            if ((b & 0xFF) == 0x81)
                return opcode == OP_1NEGATE;
        }
        if (data.length < OP_PUSHDATA1)
            return opcode == data.length;
        if (data.length < 256)
            return opcode == OP_PUSHDATA1;
        if (data.length < 65536)
            return opcode == OP_PUSHDATA2;

        // can never be used, but implemented for completeness
        return opcode == OP_PUSHDATA4;
    }

    private void write(ByteBuffer buf) {
        if (isOpCode()) {
            checkState(data == null);
            buf.put((byte) opcode);
        } else if (data != null) {
            if (opcode < OP_PUSHDATA1) {
                checkState(data.length == opcode);
                buf.put((byte) opcode);
            } else if (opcode == OP_PUSHDATA1) {
                checkState(data.length <= 0xFF);
                buf.put((byte) OP_PUSHDATA1);
                buf.put((byte) data.length);
            } else if (opcode == OP_PUSHDATA2) {
                checkState(data.length <= 0xFFFF);
                buf.put((byte) OP_PUSHDATA2);
                ByteUtils.writeInt16LE(data.length, buf);
            } else if (opcode == OP_PUSHDATA4) {
                checkState(data.length <= ScriptExecution.MAX_SCRIPT_ELEMENT_SIZE);
                buf.put((byte) OP_PUSHDATA4);
                ByteUtils.writeInt32LE(data.length, buf);
            } else {
                throw new RuntimeException("Unimplemented");
            }
            buf.put(data);
        } else {
            buf.put((byte) opcode); // smallNum
        }
    }

    public byte[] toByteArray() {
        ByteBuffer buf = ByteBuffer.allocate(size());
        write(buf);
        return buf.array();
    }

    /*
     * The size, in bytes, that this chunk would occupy if serialized into a Script.
     */
    public int size() {
        final int opcodeLength = 1;

        int pushDataSizeLength = 0;
        if (opcode == OP_PUSHDATA1) pushDataSizeLength = 1;
        else if (opcode == OP_PUSHDATA2) pushDataSizeLength = 2;
        else if (opcode == OP_PUSHDATA4) pushDataSizeLength = 4;

        final int dataLength = data == null ? 0 : data.length;

        return opcodeLength + pushDataSizeLength + dataLength;
    }

    /**
     * Get the name of the script's opcode as an int.
     * @return opcode
     */
    public int opCode() {
        return opcode;
    }

    /**
     * Get the name of the script's opcode. See {@link ScriptOpCodes#getOpCodeName(int)}.
     * @return opcode name
     */
    public String opCodeName() {
        return ScriptOpCodes.getOpCodeName(opcode);
    }

    /**
     * Get the scripts opcode as a hex-encoded string.
     * @return opcode as a hex string
     */
    public String opCodeHex() {
        return Integer.toString(opcode, 16) ;
    }

    /**
     * Get the script's push data or {@code null} if there is none.
     * @return push data or {@code null}
     */
    @Nullable
    public byte[] pushData() {
        return data;
    }

    @Override
    public String toString() {
        if (data == null)
            return getOpCodeName(opcode);
        return String.format("%s[%s]", getPushDataName(opcode), ByteUtils.formatHex(data));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScriptChunk other = (ScriptChunk) o;
        return opcode == other.opcode && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(opcode, Arrays.hashCode(data));
    }
}
