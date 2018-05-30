package io.joshworks.fstore.log.appender;

import io.joshworks.fstore.core.RuntimeIOException;
import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.DataReader;
import io.joshworks.fstore.core.io.IOUtils;
import io.joshworks.fstore.core.io.MMapStorage;
import io.joshworks.fstore.core.io.Mode;
import io.joshworks.fstore.core.io.RafStorage;
import io.joshworks.fstore.core.io.Storage;
import io.joshworks.fstore.log.BitUtil;
import io.joshworks.fstore.log.segment.Log;
import io.joshworks.fstore.log.LogFileUtils;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.appender.compaction.Compactor;
import io.joshworks.fstore.log.appender.level.Levels;
import io.joshworks.fstore.log.appender.naming.NamingStrategy;
import io.joshworks.fstore.log.segment.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Position address schema
 * <p>
 * |------------ 64bits -------------|
 * [SEGMENT_IDX] [POSITION_ON_SEGMENT]
 */
public abstract class LogAppender<T, L extends Log<T>> implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(LogAppender.class);

    private static final double SEGMENT_EXTRA_SIZE = 0.1;
    static final int COMPACTION_DISABLED = 0;


    private final File directory;
    private final Serializer<T> serializer;
    private final boolean mmap;
    private final int mmapBufferSize;
    private final Metadata metadata;
    private final DataReader dataReader;
    private final NamingStrategy namingStrategy;


    final long maxSegments;
    final long maxAddressPerSegment;

    //LEVEL0 [CURRENT_SEGMENT]
    //LEVEL1 [SEG1][SEG2]
    //LEVEL2 [SEG3][SEG4]
    //LEVEL3 ...
    final Levels<T, L> levels;

    //state
    private final State state;
//    private final History history;

    private AtomicBoolean closed = new AtomicBoolean();

    private final ScheduledExecutorService stateScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

   private final Compactor<T, L> compactor;

    private final Object LOCK = new Object();

    protected LogAppender(Builder<T> builder) {

        this.directory = builder.directory;
        this.serializer = builder.serializer;
        this.mmap = builder.mmap;
        this.mmapBufferSize = builder.mmapBufferSize;
        this.dataReader = builder.reader;
        this.namingStrategy = builder.namingStrategy;

        boolean metadataExists = LogFileUtils.metadataExists(directory);

        if (!metadataExists) {
            logger.info("Creating LogAppender");

            LogFileUtils.createRoot(directory);
            this.metadata = Metadata.create(
                    directory,
                    builder.segmentSize,
                    builder.segmentBitShift,
                    builder.maxSegmentsPerLevel,
                    builder.mmap,
                    builder.asyncFlush);

            this.state = State.empty(directory);
        } else {
            logger.info("Opening LogAppender");
            this.metadata = Metadata.readFrom(directory);
            this.state = State.readFrom(directory);
        }

//        this.history = new History(directory);

        this.maxSegments = BitUtil.maxValueForBits(Long.SIZE - metadata.segmentBitShift);
        this.maxAddressPerSegment = BitUtil.maxValueForBits(metadata.segmentBitShift);

        if (metadata.segmentBitShift >= Long.SIZE || metadata.segmentBitShift < 0) {
            //just a numeric validation, values near 64 and 0 are still nonsense
            throw new IllegalArgumentException("segmentBitShift must be between 0 and " + Long.SIZE);
        }

        this.levels = loadSegments();

        String compaction = builder.maxSegmentsPerLevel == LogAppender.COMPACTION_DISABLED ? "DISABLED" : "ENABLED";
        logger.info("Compaction is {}", compaction);

        this.compactor = new Compactor<>(builder.combiner, builder.maxSegmentsPerLevel, levels);


        logger.info("SEGMENT BIT SHIFT: {}", metadata.segmentBitShift);
        logger.info("MAX SEGMENTS: {} ({} bits)", maxSegments, Long.SIZE - metadata.segmentBitShift);
        logger.info("MAX ADDRESS PER SEGMENT: {} ({} bits)", maxAddressPerSegment, metadata.segmentBitShift);

//        this.stateScheduler.scheduleAtFixedRate(() -> state.flush(), 5, 1, TimeUnit.SECONDS);
//
//        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
//            this.flushInternal();
//            this.close();
//        }));
    }

    public static <T> Builder<T> builder(File directory, Serializer<T> serializer) {
        return new Builder<>(directory, serializer);
    }

    protected abstract L createSegment(Storage storage, Serializer<T> serializer, DataReader reader, Type type);

    protected abstract L openSegment(Storage storage, Serializer<T> serializer, DataReader reader);

    private L createSegmentInternal(int level, int indexOnLevel, long size, Type type) {
        File segmentFile = LogFileUtils.newSegmentFile(directory, namingStrategy, indexOnLevel, level);
        Storage storage = createStorage(segmentFile, size);

        return createSegment(storage, serializer, dataReader, type);
    }

    private L createCurrentSegment(long size) {
        File segmentFile = LogFileUtils.newSegmentFile(directory, namingStrategy, 0, 1);
        Storage storage = createStorage(segmentFile, size);

        return createSegment(storage, serializer, dataReader, Type.LOG_HEAD);
    }


    private Levels<T, L> loadSegments() {

        List<L> segments = new ArrayList<>();
        for(String segmentName : LogFileUtils.findSegments(directory)) {
            File segmentFile = LogFileUtils.getSegmentHandler(directory, segmentName);
            Storage storage = openStorage(segmentFile);
            L segment = openSegment(storage, serializer, dataReader);
            logger.info("Loaded segment {}", segment);
            segments.add(segment);
        }

        Map<Integer, List<L>> found = segments.stream().collect(Collectors.groupingBy(Log::level));


        Levels<T, L> loaded = Levels.load(metadata.maxSegmentsPerLevel, levelSegments);

        if (loaded.depth() == 0) { //first segment

            L initialSegment = createCurrentSegment(metadata.segmentSize);
            loaded.add(0, initialSegment);

            state.levels(loaded.segmentNames());
            state.flush();
        }

        return loaded;

    }

    private Storage openStorage(File file) {
        if (mmap)
            return new MMapStorage(file, file.length(), Mode.READ_WRITE, mmapBufferSize);
        return new RafStorage(file, file.length(), Mode.READ_WRITE);
    }

    private Storage createStorage(File file, long length) {
        //extra length for the last entry, to avoid remapping
        length = (long) (length + (length * SEGMENT_EXTRA_SIZE));
        if (mmap)
            return new MMapStorage(file, length, Mode.READ_WRITE, mmapBufferSize);
        return new RafStorage(file, length, Mode.READ_WRITE);
    }

    public synchronized void roll() {
        try {
            logger.info("Rolling appender");
            flush();

            L newSegment = createCurrentSegment(metadata.segmentSize);
            levels.promoteLevelZero(newSegment);

            state.levels(levels.segmentNames());

            state.lastRollTime(System.currentTimeMillis());
            state.flush();

            compactor.newSegmentRolled();


        } catch (Exception e) {
            throw new RuntimeIOException("Could not close segment file", e);
        }
    }

    int getSegment(long position) {
        long segmentIdx = (position >>> metadata.segmentBitShift);
        if (segmentIdx > maxSegments) {
            throw new IllegalArgumentException("Invalid segment, value cannot be greater than " + maxSegments);
        }

        return (int) segmentIdx;
    }

    long toSegmentedPosition(long segmentIdx, long position) {
        if (segmentIdx < 0) {
            throw new IllegalArgumentException("Segment index must be greater than zero");
        }
        if (segmentIdx > maxSegments) {
            throw new IllegalArgumentException("Segment index cannot be greater than " + maxSegments);
        }
        return (segmentIdx << metadata.segmentBitShift) | position;
    }

    long getPositionOnSegment(long position) {
        long mask = (1L << metadata.segmentBitShift) - 1;
        return (position & mask);
    }

    private boolean shouldRoll(L currentSegment) {
        return currentSegment.size() > metadata.segmentSize && currentSegment.size() > 0;
    }

    public long append(T data) {
        L current = levels.current();
        long positionOnSegment = current.append(data);
        long segmentedPosition = toSegmentedPosition(levels.numSegments() - 1L, positionOnSegment);
        if (positionOnSegment < 0) {
            throw new IllegalStateException("Invalid address " + positionOnSegment);
        }
        if (shouldRoll(current)) {
            roll();
            current = levels.current();
        }
        state.position(current.position());
        state.incrementEntryCount();
        return segmentedPosition;
    }


    void compact(int level) {
       compactor.compact(level);
    }

    public String name() {
        return directory.getName();
    }

    public LogIterator<T> scanner() {
        return new RollingSegmentReader(segments(Order.OLDEST), 0);
    }

    public Stream<T> stream() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(scanner(), Spliterator.ORDERED), false);
    }

    public LogIterator<T> scanner(long position) {
        return new RollingSegmentReader(segments(Order.OLDEST), position);
    }

    public long position() {
        return state.position();
    }

    public T get(long position) {
        int segmentIdx = getSegment(position);
        validateSegmentIdx(segmentIdx, position);

        long positionOnSegment = getPositionOnSegment(position);
        L segment = levels.get(segmentIdx);
        if (segment != null) {
            return segment.get(positionOnSegment);
        }
        return null;
    }

    void validateSegmentIdx(int segmentIdx, long pos) {
        if (segmentIdx < 0 || segmentIdx > levels.numSegments()) {
            throw new IllegalArgumentException("No segment for address " + pos + " (segmentIdx: " + segmentIdx + "), available segments: " + levels.numSegments());
        }
    }

    public long size() {
        Stream<L> stream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(segments(Order.NEWEST), Spliterator.ORDERED), false);
        return stream.mapToLong(Log::size).sum();
    }

    public Stream<L> streamSegments(Order order) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(segments(order), Spliterator.ORDERED), false);
    }

    public long size(int level) {
        return segments(level).stream().mapToLong(Log::size).sum();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        logger.info("Closing log appender {}", directory.getName());
        stateScheduler.shutdown();

        L currentSegment = levels.current();
        if (currentSegment != null) {
            IOUtils.flush(currentSegment);
            state.position(currentSegment.position());
            state.levels(levels.segmentNames());
        }

        state.flush();
        state.close();

        streamSegments(Order.OLDEST).forEach(segment -> {
            logger.info("Closing segment {}", segment.name());
            IOUtils.closeQuietly(segment);
        });
    }

    public void flush() {
        if (closed.get()) {
            return;
        }
        if (metadata.asyncFlush)
            executor.execute(this::flushInternal);
        else
            this.flushInternal();

    }

    private void flushInternal() {
        if (closed.get()) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            levels.current().flush();
            logger.info("Flush took {}ms", System.currentTimeMillis() - start);
        } catch (IOException e) {
            throw RuntimeIOException.of(e);
        }
    }

    public List<String> segmentsNames() {
        return streamSegments(Order.OLDEST).map(Log::name).collect(Collectors.toList());
    }

    public long entries() {
        return this.state.entryCount();
    }

    public String currentSegment() {
        return levels.current().name();
    }

    public L current() {
        return levels.current();
    }

    public Iterator<L> segments(Order order) {
        return levels.segments(order);
    }

    public List<L> segments(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Level must be at least zero");
        }
        if (level > levels.depth()) {
            throw new IllegalArgumentException("No such level " + level + ", current depth: " + levels.depth());
        }
        return levels.segments(level);
    }

    public int depth() {
        return levels.depth();
    }

    public Path directory() {
        return directory.toPath();
    }

    private class RollingSegmentReader implements LogIterator<T> {

        private final Iterator<L> segments;
        private LogIterator<T> current;
        private int segmentIdx;

        RollingSegmentReader(Iterator<L> segments, long position) {
            this.segments = segments;
            this.segmentIdx = getSegment(position);
            validateSegmentIdx(segmentIdx, position);
            long positionOnSegment = getPositionOnSegment(position);

            L segment = null;
            for (int i = 0; i <= segmentIdx; i++) {
                segment = segments.next();
            }

            this.current = segment.iterator(positionOnSegment);
        }

        @Override
        public long position() {
            return toSegmentedPosition(segmentIdx, current.position());
        }

        @Override
        public boolean hasNext() {
            if (current == null) {
                return false;
            }
            boolean hasNext = current.hasNext();
            if (!hasNext) {
                if (!segments.hasNext()) {
                    return false;
                }
                current = segments.next().iterator();
                return current.hasNext();
            }
            return true;
        }

        @Override
        public T next() {
            return current.next();
        }
    }
}
