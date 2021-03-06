package io.joshworks.eventry.index.disk;

import io.joshworks.eventry.index.Index;
import io.joshworks.eventry.index.IndexEntry;
import io.joshworks.eventry.index.Range;
import io.joshworks.eventry.index.filter.BloomFilter;
import io.joshworks.eventry.index.midpoint.Midpoints;
import io.joshworks.fstore.core.RuntimeIOException;
import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.DataReader;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.eventry.index.filter.BloomFilterHasher;
import io.joshworks.eventry.index.midpoint.Midpoint;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.Iterators;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.segment.Type;
import io.joshworks.fstore.log.segment.block.BlockSegment;
import io.joshworks.fstore.serializer.Serializers;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

public class IndexSegment extends BlockSegment<IndexEntry, IndexBlock> implements Index {

    BloomFilter<Long> filter;
    final Midpoints midpoints;
    final File directory;
    private static final int MAX_BLOCK_SIZE = 4096;

    private static final double FALSE_POSITIVE_PROB = 0.01;

    IndexSegment(Storage storage,
                        Serializer<IndexBlock> serializer,
                        DataReader reader,
                        String magic,
                        Type type,
                        File directory,
                        int numElements) {
        super(storage, new IndexEntrySerializer(), serializer, MAX_BLOCK_SIZE, reader, magic, type);
        this.directory = directory;
        this.midpoints = new Midpoints(directory, name());
        this.filter = BloomFilter.openOrCreate(directory, name(), numElements, FALSE_POSITIVE_PROB, BloomFilterHasher.Murmur64(Serializers.LONG));
    }

    @Override
    protected synchronized long writeBlock() {
        IndexBlock block = currentBlock();
        long position = position();
        if (block.isEmpty()) {
            return position;
        }

        Midpoint head = new Midpoint(block.first(), position);
        Midpoint tail = new Midpoint(block.last(), position);
        midpoints.add(head, tail);

        return super.writeBlock();
    }

    @Override
    public long append(IndexEntry data) {
        filter.add(data.stream);
        return super.append(data);
    }

    @Override
    public synchronized void flush() {
        super.flush(); //flush super first, so writeBlock is called
        midpoints.write();
        filter.write();
    }

    @Override
    public void delete() {
        super.delete();
        filter.delete();
        midpoints.delete();
    }

    void newBloomFilter(long numElements) {
        this.filter = BloomFilter.openOrCreate(directory, name(), numElements, FALSE_POSITIVE_PROB, BloomFilterHasher.Murmur64(Serializers.LONG));
    }

    private boolean mightHaveEntries(Range range) {
        return midpoints.inRange(range) && filter.contains(range.stream);
    }


    @Override
    public LogIterator<IndexEntry> iterator(Direction direction, Range range) {
        if (!mightHaveEntries(range)) {
            return Iterators.empty();
        }

        Midpoint lowBound = midpoints.getMidpointFor(range.start());
        if (lowBound == null) {
            return Iterators.empty();
        }

        LogIterator<IndexEntry> logIterator = iterator(lowBound.position, direction);
        return new RangeIndexEntryIterator(range, logIterator);
    }

    @Override
    public Stream<IndexEntry> stream(Direction direction, Range range) {
        return Iterators.stream(iterator(direction, range));
    }

    @Override
    public Optional<IndexEntry> get(long stream, int version) {
        Range range = Range.of(stream, version, version + 1);
        if (!mightHaveEntries(range)) {
            return Optional.empty();
        }

        IndexEntry start = range.start();
        Midpoint lowBound = midpoints.getMidpointFor(start);
        if (lowBound == null) {//false positive on the bloom filter and entry was within range of this segment
            return Optional.empty();
        }

        IndexBlock block = getBlock(lowBound.position);
        List<IndexEntry> entries = block.entries();
        int idx = Collections.binarySearch(entries, start);
        if(idx < 0) { //if not exact match, wasn't found
            return Optional.empty();
        }
        IndexEntry found = entries.get(idx);
        if (found == null || found.stream != stream && found.version != version) { //sanity check
            throw new IllegalStateException("Inconsistent index");
        }
        return Optional.of(found);

    }

    @Override
    public int version(long stream) {
        Range range = Range.allOf(stream);
        if (!mightHaveEntries(range)) {
            return IndexEntry.NO_VERSION;
        }

        IndexEntry end = range.end();
        Midpoint lowBound = midpoints.getMidpointFor(end);
        if (lowBound == null) {//false positive on the bloom filter and entry was within range of this segment
            return IndexEntry.NO_VERSION;
        }

        IndexBlock block = getBlock(lowBound.position);
        List<IndexEntry> entries = block.entries();
        int idx = Collections.binarySearch(entries, end);
        idx = idx >= 0 ? idx : Math.abs(idx) - 2;
        IndexEntry lastVersion = entries.get(idx);
        if (lastVersion.stream != stream) { //false positive on the bloom filter
            return IndexEntry.NO_VERSION;
        }
        return lastVersion.version;
    }

    @Override
    protected IndexBlock createBlock(Serializer<IndexEntry> serializer, int maxBlockSize) {
        return new IndexBlock(maxBlockSize);
    }

    @Override
    public String toString() {
        return super.toString();
    }

    private static final class RangeIndexEntryIterator implements LogIterator<IndexEntry> {

        private final IndexEntry end;
        private final IndexEntry start;
        private final LogIterator<IndexEntry> segmentIterator;
        private IndexEntry current;

        private RangeIndexEntryIterator(Range range, LogIterator<IndexEntry> logIterator) {
            this.end = range.end();
            this.start = range.start();
            this.segmentIterator = logIterator;

            //initial load skipping less than queuedTime
            while (logIterator.hasNext()) {
                IndexEntry next = logIterator.next();
                if (next.greatOrEqualsTo(start)) {
                    current = next;
                    break;
                }
            }
        }

        @Override
        public boolean hasNext() {
            boolean hasNext = current != null && current.lessThan(end);
            if(!hasNext) {
                close();
            }
            return hasNext;
        }

        @Override
        public void close() {
            try {
                segmentIterator.close();
            } catch (IOException e) {
                throw RuntimeIOException.of(e);
            }
        }

        @Override
        public IndexEntry next() {
            if (current == null) {
                close();
                throw new NoSuchElementException();
            }
            IndexEntry curr = current;
            current = segmentIterator.hasNext() ? segmentIterator.next() : null;
            return curr;
        }

        @Override
        public long position() {
            return segmentIterator.position();
        }
    }

}
