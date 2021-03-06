package io.joshworks.eventry.stream;

import io.joshworks.eventry.LRUCache;
import io.joshworks.eventry.hash.Murmur3Hash;
import io.joshworks.eventry.hash.XXHash;
import io.joshworks.eventry.index.StreamHasher;
import io.joshworks.eventry.index.IndexEntry;
import io.joshworks.eventry.utils.StringUtils;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Streams implements Closeable {

    public static final String STREAM_WILDCARD = "*";
    //TODO LRU cache ? there's no way of getting item by stream name, need to use an indexed lsm-tree
    //LRU map that reads the last version from the index
    private final LRUCache<Long, AtomicInteger> versions;
    private final Map<Long, StreamMetadata> streamsMap = new ConcurrentHashMap<>();
    private final StreamHasher hasher;

    public Streams(int versionLruCacheSize, Function<Long, Integer> versionFetcher) {
        this.versions = new LRUCache<>(versionLruCacheSize, streamHash -> new AtomicInteger(versionFetcher.apply(streamHash)));
        this.hasher = new StreamHasher(new XXHash(), new Murmur3Hash());
    }

    public Optional<StreamMetadata> get(String stream) {
        return get(hashOf(stream));
    }

    public Optional<StreamMetadata> get(long streamHash) {
        return Optional.ofNullable(streamsMap.get(streamHash));
    }

    public List<StreamMetadata> all() {
        return new ArrayList<>(streamsMap.values());
    }

    public long hashOf(String stream) {
        return hasher.hash(stream);
    }

    public boolean create(StreamMetadata stream) {
        Objects.requireNonNull(stream);
        StringUtils.requireNonBlank(stream.name);
        versions.set(stream.hash, new AtomicInteger(IndexEntry.NO_VERSION));
        return streamsMap.putIfAbsent(stream.hash, stream) == null;
    }

    public StreamMetadata remove(long streamHash) {
        return streamsMap.remove(streamHash);
    }

    //Only supports 'startingWith' wildcard
    //EX: users-*
    public Set<String> streamMatching(String value) {
        if(value == null) {
            return new HashSet<>();
        }
        //wildcard
        if(value.endsWith(STREAM_WILDCARD)) {
            final String prefix = value.substring(0, value.length() - 1);
            return streamsMap.values().stream()
                    .filter(stream -> stream.name.startsWith(prefix))
                    .map(stream -> stream.name)
                    .collect(Collectors.toSet());
        }

        return streamsMap.values().stream()
                .filter(stream -> stream.name.equals(value))
                .map(stream -> stream.name)
                .collect(Collectors.toSet());


    }

    public int version(long stream) {
        return versions.getOrElse(stream, new AtomicInteger(IndexEntry.NO_VERSION)).get();
    }

    public int tryIncrementVersion(long stream, int expected) {
        AtomicInteger versionCounter = versions.getOrElse(stream, new AtomicInteger(IndexEntry.NO_VERSION));
        if(expected < 0) {
            return versionCounter.incrementAndGet();
        }
        int newValue = expected + 1;
        if(!versionCounter.compareAndSet(expected, newValue)) {
            throw new IllegalArgumentException("Version mismatch: expected stream " + stream + " version is higher than expected: " + expected);
        }
        return newValue;
    }



    @Override
    public void close() {

    }

}
