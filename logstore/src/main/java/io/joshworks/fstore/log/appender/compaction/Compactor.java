package io.joshworks.fstore.log.appender.compaction;

import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.io.DataReader;
import io.joshworks.fstore.core.seda.EventContext;
import io.joshworks.fstore.core.seda.SedaContext;
import io.joshworks.fstore.core.seda.Stage;
import io.joshworks.fstore.log.LogFileUtils;
import io.joshworks.fstore.log.appender.SegmentFactory;
import io.joshworks.fstore.log.appender.StorageProvider;
import io.joshworks.fstore.log.appender.level.Levels;
import io.joshworks.fstore.log.appender.merge.SegmentCombiner;
import io.joshworks.fstore.log.appender.naming.NamingStrategy;
import io.joshworks.fstore.log.segment.Log;
import io.joshworks.fstore.log.segment.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Compactor<T, L extends Log<T>> {

    private static final Logger logger = LoggerFactory.getLogger(Compactor.class);

    static final String COMPACTION_CLEANUP_STAGE = "compaction-cleanup";
    static final String COMPACTION_MANAGER = "compaction-manager";

    private File directory;
    private final SegmentCombiner<T> segmentCombiner;

    private final SegmentFactory<T, L> segmentFactory;
    private final StorageProvider storageProvider;
    private Serializer<T> serializer;
    private DataReader dataReader;
    private NamingStrategy namingStrategy;
    private final int maxSegmentsPerLevel;
    private final String magic;
    private Levels<T, L> levels;
    private final SedaContext sedaContext;
    private final boolean threadPerLevel;

    private final Set<L> compacting = new HashSet<>();

    private final CompactionTask<T, L> compactionTask = new CompactionTask<>();

    public Compactor(File directory,
                     SegmentCombiner<T> segmentCombiner,
                     SegmentFactory<T, L> segmentFactory,
                     StorageProvider storageProvider,
                     Serializer<T> serializer,
                     DataReader dataReader,
                     NamingStrategy namingStrategy,
                     int maxSegmentsPerLevel,
                     String magic,
                     Levels<T, L> levels,
                     SedaContext sedaContext,
                     boolean threadPerLevel) {
        this.directory = directory;
        this.segmentCombiner = segmentCombiner;
        this.segmentFactory = segmentFactory;
        this.storageProvider = storageProvider;
        this.serializer = serializer;
        this.dataReader = dataReader;
        this.namingStrategy = namingStrategy;
        this.maxSegmentsPerLevel = maxSegmentsPerLevel;
        this.magic = magic;
        this.levels = levels;
        this.sedaContext = sedaContext;
        this.threadPerLevel = threadPerLevel;

        sedaContext.addStage(COMPACTION_CLEANUP_STAGE, this::cleanup, new Stage.Builder().corePoolSize(1).maximumPoolSize(1));
        sedaContext.addStage(COMPACTION_MANAGER, this::compact, new Stage.Builder().corePoolSize(1).maximumPoolSize(1));
    }

    public void forceCompaction(int level) {
        if (level <= 0) {
            throw new IllegalArgumentException("Level must be greater than 1");
        }
        sedaContext.submit(COMPACTION_MANAGER, new CompactionRequest(level, true));
    }

    public void requestCompaction(int level) {
        if (level <= 0) {
            throw new IllegalArgumentException("Level must be greater than 1");
        }
        sedaContext.submit(COMPACTION_MANAGER, new CompactionRequest(level, false));
    }

    private void compact(EventContext<CompactionRequest> event) {
        int level = event.data.level;
        boolean force = event.data.force;

        synchronized (this) {
            if (!requiresCompaction(level) && !force) {
                return;
            }
            List<L> segmentsForCompaction = segmentsForCompaction(level);
            if (segmentsForCompaction.size() <= 1) {
                long count = compacting.stream().filter(l -> l.level() == level).count();
                logger.info("Nothing to compact on level {} (compacting {})", level, count);
                return;
            }
            compacting.addAll(segmentsForCompaction);

            logger.info("Compacting level {}", level);

            File targetFile = LogFileUtils.newSegmentFile(directory, namingStrategy, level + 1);

            String stageName = stageName(level);
            if (!sedaContext.stages().contains(stageName)) {
                sedaContext.addStage(stageName, compactionTask, new Stage.Builder().corePoolSize(1).maximumPoolSize(1));
            }
            event.submit(stageName, new CompactionEvent<>(segmentsForCompaction, segmentCombiner, targetFile, segmentFactory, storageProvider, serializer, dataReader, level, magic));
        }
    }

    private String stageName(int level) {
        return threadPerLevel ? "compaction-level-" + level : "compaction";
    }

    private void cleanup(EventContext<CompactionResult<T, L>> context) {
        CompactionResult<T, L> result = context.data;
        if (!result.successful()) {
            //TODO
            logger.error("Compaction error", result.exception);
            logger.info("Deleting failed merge result segment");
            result.target.delete();
            return;
        }

        levels.merge(result.sources, result.target);
        compacting.removeAll(result.sources);

        context.submit(COMPACTION_MANAGER, new CompactionRequest(result.level, false));
        context.submit(COMPACTION_MANAGER, new CompactionRequest(result.level + 1, false));

        Segment.deleteAll(result.sources);
        for (L segment : result.sources) {
            logger.info("Deleting {}", segment.name());
            segment.delete();
            logger.info("Deleted {}", segment.name());
        }
    }

    private synchronized boolean requiresCompaction(int level) {
        if (maxSegmentsPerLevel <= 0 || level < 0) {
            return false;
        }

        long compactingForLevel = new ArrayList<>(compacting).stream().filter(l -> l.level() == level).count();
        int levelSize = levels.segments(level).size();
        return levelSize - compactingForLevel >= levels.compactionThreshold();
    }

    private List<L> segmentsForCompaction(int level) {
        List<L> toBeCompacted = new ArrayList<>();
        if (level <= 0) {
            throw new IllegalArgumentException("Level must be greater than zero");
        }
        for (L segment : levels.segments(level)) {
            if (!compacting.contains(segment)) {
                toBeCompacted.add(segment);
            }
            if (toBeCompacted.size() >= levels.compactionThreshold()) {
                break;
            }
        }
        return toBeCompacted;
    }

    private static class CompactionRequest {
        private final int level;
        private final boolean force;

        private CompactionRequest(int level, boolean force) {
            this.level = level;
            this.force = force;
        }
    }

}
