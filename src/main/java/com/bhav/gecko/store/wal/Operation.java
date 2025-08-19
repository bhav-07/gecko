package com.bhav.gecko.store.wal;

public enum Operation {
    PUT((byte) 1),
    GET((byte) 2),
    DELETE((byte) 3);

    private final byte value;

    Operation(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }

    public static Operation fromByte(byte value) {
        for (Operation op : values()) {
            if (op.value == value)
                return op;
        }
        throw new IllegalArgumentException("Unknown operation: " + value);
    }
}
