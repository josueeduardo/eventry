package io.joshworks.eventry.hash;

import java.nio.ByteBuffer;

public interface Hash {

    int hash32(ByteBuffer data);
    int hash32(ByteBuffer data, int seed);

    int hash32(byte[] data);
    long hash64(byte[] data);
    int hash32(byte[] data, int seed);

}
