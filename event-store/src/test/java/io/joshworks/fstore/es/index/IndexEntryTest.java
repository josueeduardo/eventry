package io.joshworks.fstore.es.index;

import org.junit.Test;

import static org.junit.Assert.*;

public class IndexEntryTest {

    @Test
    public void higher_stream_version_is_greater() {

        IndexEntry first = IndexEntry.of(1, 1, 0);
        IndexEntry second = IndexEntry.of(1, 2, 0);

        assertTrue(first.compareTo(second) < 0);
    }

    @Test
    public void higher_stream_hash_is_greater() {

        IndexEntry first = IndexEntry.of(1, 0, 0);
        IndexEntry second = IndexEntry.of(2, 0, 0);

        assertTrue(first.compareTo(second) < 0);
    }

    @Test
    public void position_isnt_used_in_compareTo() {

        IndexEntry first = IndexEntry.of(1, 0, 0);
        IndexEntry second = IndexEntry.of(1, 0, 1);

        assertEquals(0, first.compareTo(second));
    }
}