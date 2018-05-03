package io.joshworks.fstore.log;

import java.io.Closeable;
import java.util.stream.Stream;

public interface Log<T> extends Writer<T>, Closeable {

    int ENTRY_HEADER_SIZE = Integer.BYTES * 2; //length + crc32
    byte[] EOF = new byte[]{0xFFFFFFFF, 0x00000000}; //eof header, -1 length, 0 crc

    String name();

    Scanner<T> scanner();

    Stream<T> stream();

    Scanner<T> scanner(long position);

    long position();

    T get(long position);

    long size();

    long checkIntegrity(long lastKnownPosition);

    void delete();

    void roll();

    boolean readOnly();

}
