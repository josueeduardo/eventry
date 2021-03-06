package io.joshworks.eventry.index;

import io.joshworks.fstore.codec.snappy.SnappyCodec;
import io.joshworks.fstore.core.Codec;
import io.joshworks.fstore.core.io.IOUtils;
import io.joshworks.eventry.index.disk.IndexAppender;
import io.joshworks.eventry.index.disk.IndexCompactor;
import io.joshworks.eventry.index.disk.IndexEntrySerializer;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.Iterators;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.PollingSubscriber;
import io.joshworks.fstore.log.appender.LogAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TableIndex implements Index {

    private static final Logger logger = LoggerFactory.getLogger(TableIndex.class);
    public static final int DEFAULT_FLUSH_THRESHOLD = 1000000;
    public static final boolean DEFAULT_USE_COMPRESSION = true;
    private static final String INDEX_DIR = "index";
    private static final String INDEX_WRITER = "index-writer";
    private final int flushThreshold; //TODO externalize

    //    private final EventLog log;
    private final IndexAppender diskIndex;
    private MemIndex memIndex = new MemIndex();

    private final Set<DiskMemIndexPoller> pollers = new HashSet<>();

    public TableIndex(File rootDirectory) {
        this(rootDirectory, DEFAULT_FLUSH_THRESHOLD, DEFAULT_USE_COMPRESSION);
    }

    public TableIndex(File rootDirectory, int flushThreshold, boolean useCompression) {
        if (flushThreshold < 1000) {//arbitrary number
            throw new IllegalArgumentException("Flush threshold must be at least 1000");
        }

        Codec codec = useCompression ? new SnappyCodec() : Codec.noCompression();
        diskIndex = new IndexAppender(LogAppender
                .builder(new File(rootDirectory, INDEX_DIR), new IndexEntrySerializer())
                .compactionStrategy(new IndexCompactor())
                .maxSegmentsPerLevel(2)
                .maxRecordSize(4096 * 4)
                .segmentSize(flushThreshold * IndexEntry.BYTES)
                .namingStrategy(new IndexAppender.IndexNaming()), flushThreshold, codec);

        this.flushThreshold = flushThreshold;
    }

    //returns true if flushed to disk
    public FlushInfo add(long stream, int version, long position) {
        if (version <= IndexEntry.NO_VERSION) {
            throw new IllegalArgumentException("Version must be greater than or equals to zero");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Position must be greater than zero");
        }
        IndexEntry entry = IndexEntry.of(stream, version, position);
        memIndex.add(entry);
        if (memIndex.size() >= flushThreshold) {
            var flushInfo = writeToDisk();
            memIndex.close();
            memIndex = new MemIndex();
            return flushInfo;
        }
        return null;
    }

    //only single write can happen at time
    public FlushInfo writeToDisk() {
        logger.info("Writing index to disk");
        if (memIndex.isEmpty()) {
            return null;
        }

        long start = System.currentTimeMillis();
        memIndex.stream(Direction.FORWARD).forEach(diskIndex::append);
        diskIndex.roll();
        long timeTaken = System.currentTimeMillis() - start;
        logger.info("Flush completed in {}ms", timeTaken);
        return new FlushInfo(memIndex.size(), timeTaken);
    }

    @Override
    public int version(long stream) {
        int version = memIndex.version(stream);
        if (version > IndexEntry.NO_VERSION) {
            return version;
        }

        return diskIndex.version(stream);
    }

    public long size() {
        return diskIndex.entries() + memIndex.size();
    }

    @Override
    public void close() {
//        this.flush(); //no need to flush, just reload from disk on startup
        memIndex.close();
        diskIndex.close();
        for (DiskMemIndexPoller poller : pollers) {
            IOUtils.closeQuietly(poller);
        }
        pollers.clear();

    }

    @Override
    public LogIterator<IndexEntry> iterator(Direction direction) {
        LogIterator<IndexEntry> cacheIterator = memIndex.iterator(direction);
        LogIterator<IndexEntry> diskIterator = diskIndex.iterator(direction);

        return joiningDiskAndMem(diskIterator, cacheIterator);
    }

    @Override
    public LogIterator<IndexEntry> iterator(Direction direction, Range range) {
        LogIterator<IndexEntry> cacheIterator = memIndex.iterator(direction, range);
        LogIterator<IndexEntry> diskIterator = diskIndex.iterator(direction, range);

        return joiningDiskAndMem(diskIterator, cacheIterator);
    }

    @Override
    public Stream<IndexEntry> stream(Direction direction) {
        return Iterators.stream(iterator(direction));
    }

    @Override
    public Stream<IndexEntry> stream(Direction direction, Range range) {
        return Iterators.stream(iterator(direction, range));
    }

    @Override
    public Optional<IndexEntry> get(long stream, int version) {
        Optional<IndexEntry> fromMemory = memIndex.get(stream, version);
        if (fromMemory.isPresent()) {
            return fromMemory;
        }
        return diskIndex.get(stream, version);
    }

    private LogIterator<IndexEntry> joiningDiskAndMem(LogIterator<IndexEntry> diskIterator, LogIterator<IndexEntry> memIndex) {
        return Iterators.concat(Arrays.asList(diskIterator, memIndex));
    }

    public FlushInfo flush() {
        FlushInfo flushInfo = writeToDisk();
        memIndex.close();
        memIndex = new MemIndex();
        return flushInfo;
    }

    public PollingSubscriber<IndexEntry> poller(long stream) {
        return poller(Set.of(stream));
    }

    //TODO how to mark last read indexEntry ?
    //timestamp / position (how about the mem items ?) / version (of each stream individually, then crash could cause repeated)
    public PollingSubscriber<IndexEntry> poller(long stream, int lastVersion) {
        throw new UnsupportedOperationException("Implement me");
//        return poller(Set.of(stream));
    }

    public PollingSubscriber<IndexEntry> poller(Set<Long> streams) {
        return new StreamIndexPoller(new DiskMemIndexPoller(diskIndex.poller()), streams);
    }

    private class StreamIndexPoller implements PollingSubscriber<IndexEntry> {

        private final PollingSubscriber<IndexEntry> poller;
        private final Map<Long, Integer> streamsRead;
        private IndexEntry lastEntry;

        StreamIndexPoller(PollingSubscriber<IndexEntry> poller, Set<Long> streams) {
            this.poller = poller;
            this.streamsRead = streams.stream().collect(Collectors.toMap(aLong -> aLong, aLong -> -1));
        }

        private IndexEntry poolAndUpdateMap() throws InterruptedException {
            while (!poller.headOfLog()) {
                IndexEntry indexEntry = poller.poll();
                if (indexEntry == null) {
                    return null;
                }
                if (streamMatch(indexEntry)) {
                    streamsRead.computeIfPresent(indexEntry.stream, (k, v) -> indexEntry.version);
                    lastEntry = indexEntry;
                    return indexEntry;
                }
            }
            return null;
        }

        private boolean streamMatch(IndexEntry indexEntry) {
            return streamsRead.getOrDefault(indexEntry.stream, Integer.MAX_VALUE) < indexEntry.version;
        }

        @Override
        public IndexEntry peek() throws InterruptedException {
            if (lastEntry != null) {
                return lastEntry;
            }
            while (!poller.headOfLog()) {
                IndexEntry indexEntry = poller.poll();
                if (indexEntry == null) {
                    return null;
                }
                if (streamMatch(indexEntry)) {
                    lastEntry = indexEntry;
                    return indexEntry;
                }
            }
            return null;
        }

        @Override
        public IndexEntry poll() throws InterruptedException {
            return poll(-1, TimeUnit.SECONDS);
        }

        @Override
        public IndexEntry poll(long limit, TimeUnit timeUnit) throws InterruptedException {
            IndexEntry indexEntry = poolAndUpdateMap();
            if (indexEntry != null) {
                lastEntry = null; //invalidate future peek
            }
            return indexEntry;
        }

        @Override
        public IndexEntry take() throws InterruptedException {
            IndexEntry indexEntry;
            do {
                indexEntry = poller.take();

            } while (indexEntry == null || !streamMatch(indexEntry));
            streamsRead.computeIfPresent(indexEntry.stream, (k, v) -> v + 1);

            lastEntry = null; //invalidate future peek
            return indexEntry;
        }

        @Override
        public boolean headOfLog() {
            return poller.headOfLog();
        }

        @Override
        public boolean endOfLog() {
            return poller.endOfLog();
        }

        @Override
        public long position() {
            return poller.position();
        }

        @Override
        public void close() throws IOException {
            poller.close();
            //TODO store state ??
        }
    }

    private class DiskMemIndexPoller implements PollingSubscriber<IndexEntry> {

        private final PollingSubscriber<IndexEntry> diskPoller;
        private PollingSubscriber<IndexEntry> memPoller;
        private long readFromMemory;

        private DiskMemIndexPoller(PollingSubscriber<IndexEntry> diskPoller) {
            this.diskPoller = diskPoller;
            this.memPoller = memIndex.poller();
        }

        @Override
        public IndexEntry peek() throws InterruptedException {
            return getIndexEntry(poller -> {
                try {
                    return poller.peek();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public IndexEntry poll() throws InterruptedException {
            return getIndexEntry(poller -> {
                try {
                    return poller.poll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }


        @Override
        public IndexEntry poll(long limit, TimeUnit timeUnit) throws InterruptedException {
            return getIndexEntry(poller -> {
                try {
                    return poller.poll(limit, timeUnit);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public IndexEntry take() throws InterruptedException {
            return getIndexEntry(poller -> {
                try {
                    return poller.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }

        private synchronized IndexEntry getIndexEntry(Function<PollingSubscriber<IndexEntry>, IndexEntry> func) throws InterruptedException {
            if (!diskPoller.headOfLog()) {
                memPoller = newMemPoller();
                while (readFromMemory > 0) {
                    IndexEntry polled = diskPoller.take();
                    if (polled == null) {
                        throw new IllegalStateException("Polled value was null");
                    }
                    readFromMemory--;
                }
                if (!diskPoller.headOfLog()) {
                    return diskPoller.poll();
                }
                IndexEntry polled = diskPoller.poll(3, TimeUnit.SECONDS);
                if (polled != null) {
                    return polled;
                }
            }

            if (memPoller.endOfLog()) {
                memPoller = newMemPoller();
            }
            IndexEntry polled = func.apply(memPoller);
            if (polled == null && memPoller.endOfLog()) { //closed while waiting for data
                memPoller = newMemPoller();
                return getIndexEntry(func);
            }
            readFromMemory++;
            return polled;
        }

        @Override
        public synchronized boolean headOfLog() {
            return diskPoller.headOfLog() && memPoller.headOfLog();
        }

        @Override
        public boolean endOfLog() {
            return false;
        }

        @Override
        public long position() {
            return 0;
        }

        @Override
        public synchronized void close() {
            IOUtils.closeQuietly(diskPoller);
            IOUtils.closeQuietly(memPoller);
        }

        private PollingSubscriber<IndexEntry> newMemPoller() {
            IOUtils.closeQuietly(memPoller);
            return memIndex.poller();
        }

    }


    public class FlushInfo {
        public int entries;
        public final long timeTaken;

        private FlushInfo(int entries, long timeTaken) {
            this.entries = entries;
            this.timeTaken = timeTaken;
        }
    }
}