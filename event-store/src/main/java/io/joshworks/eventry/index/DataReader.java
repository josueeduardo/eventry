package io.joshworks.eventry.index;

import java.util.SortedSet;

public interface DataReader {

    SortedSet<IndexEntry> read(long position);

}
