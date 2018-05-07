package io.joshworks.fstore.log.block;

import io.joshworks.fstore.core.Codec;
import io.joshworks.fstore.core.Serializer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class Block<T> implements Iterable<T> {

    private static final double BLOCK_SIZE_EXTRA = 0.1; //10% of the size to avoid resizing

    protected final Serializer<T> serializer;
    protected final int maxSize;
    protected ByteBuffer buffer;

    protected boolean readOnly;
    protected List<Integer> lengths = new ArrayList<>();

    private final List<T> cached = new ArrayList<>();

    public Block(Serializer<T> serializer, int maxSize) {
        this.serializer = serializer;
        this.maxSize = maxSize;
        int blockSize = (int) (maxSize + (maxSize * BLOCK_SIZE_EXTRA));
        this.buffer = ByteBuffer.allocate(blockSize);
    }

    protected Block(Serializer<T> serializer, List<Integer> lengths, ByteBuffer data) {
        this.serializer = serializer;
        this.lengths = lengths;
        this.buffer = data;
        this.readOnly = true;
        this.maxSize = -1;
    }

    public boolean add(T data) {
        if (readOnly) {
            throw new IllegalStateException("Block is read only");
        }
        ByteBuffer bb = serializer.toBytes(data);
        lengths.add(bb.limit());

        if (bb.limit() > buffer.remaining()) {
            ByteBuffer tempBb = ByteBuffer.allocate(buffer.capacity() + bb.limit());
            buffer.flip();
            tempBb.put(buffer).put(bb);
            buffer = tempBb;
            return true;
        }

        buffer.put(bb);
        return buffer.position() >= maxSize;
    }

    public ByteBuffer readOnlyBuffer() {
        ByteBuffer readOnlyBuffer = buffer.asReadOnlyBuffer();
        //if not readonly, this block is being written to, flip it
        return readOnly ? readOnlyBuffer : (ByteBuffer) readOnlyBuffer.flip();
    }

    public ByteBuffer pack() {
        return (ByteBuffer) buffer.asReadOnlyBuffer().flip();
    }

    public ByteBuffer pack(Codec codec) {
        ByteBuffer original = pack();
        return codec.compress(original);
    }

    public int entryCount() {
        return lengths.size();
    }

    public List<T> entries() {
        if(!cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        ByteBuffer readBuffer = buffer.asReadOnlyBuffer();
        readBuffer.position(0);
        for (Integer length : lengths) {
            T entry = readEntry(readBuffer, serializer, length);
            cached.add(entry);
        }
        return new ArrayList<>(cached);
    }

    protected static <T> T readEntry(ByteBuffer data, Serializer<T> serializer, int length) {
        if(data.remaining() == 0 || data.remaining() < length) {
            return null;
        }
        byte[] entryData = new byte[length];
        data.get(entryData);
        return serializer.fromBytes(ByteBuffer.wrap(entryData));
    }

    public T first() {
        return get(0);
    }

    public T last() {
        return get(lengths.size() - 1);
    }

    public T get(int pos) {
        if (pos >= lengths.size() || lengths.get(pos) < 0) {
            return null;
        }

        int position = 0;
        for (int i = 0; i < pos; i++) {
            position += lengths.get(i);
        }

        ByteBuffer readOnlyBb = buffer.asReadOnlyBuffer();
        readOnlyBb.position(position);
        return readEntry(readOnlyBb, serializer, lengths.get(pos));
    }

    public int size() {
        return Integer.BYTES + (Integer.BYTES * lengths.size()) + buffer.limit();
    }

    @Override
    public Iterator<T> iterator() {
        ByteBuffer readBuffer = buffer.asReadOnlyBuffer();
        readBuffer.position(0);
        return new BlockIterator<>(serializer, readBuffer, lengths);
    }

    public static <T> Block<T> newBlock(Serializer<T> serializer) {
        return new Block<>(serializer, 4096);
    }

    public static <T> Block<T> newBlock(Serializer<T> serializer, int maxSize) {
        return new Block<>(serializer, maxSize);
    }

    public boolean readOnly() {
        return readOnly;
    }

    public boolean isEmpty() {
        return lengths.isEmpty();
    }

    List<Integer> entriesLength() {
        return lengths;
    }

    private static class BlockIterator<T> implements Iterator<T> {

        private final Serializer<T> serializer;
        private final ByteBuffer data;
        private final List<Integer> entriesLength;
        private int index;

        private BlockIterator(Serializer<T> serializer, ByteBuffer data, List<Integer> entriesLength) {
            this.serializer = serializer;
            this.data = data;
            this.entriesLength = entriesLength;
        }

        @Override
        public boolean hasNext() {
            return index < entriesLength.size();
        }

        @Override
        public T next() {
            if(index >= entriesLength.size()) {
                throw new NoSuchElementException();
            }
            int length = entriesLength.get(index++);
            return readEntry(data, serializer, length);

        }
    }

}