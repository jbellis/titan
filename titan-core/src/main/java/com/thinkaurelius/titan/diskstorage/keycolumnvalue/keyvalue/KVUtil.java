package com.thinkaurelius.titan.diskstorage.keycolumnvalue.keyvalue;

import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreTransaction;
import com.thinkaurelius.titan.diskstorage.util.RecordIterator;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayEntry;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayEntryList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Utility methods for interacting with {@link KeyValueStore}.
 *
 * @author Matthias Br&ouml;cheler (me@matthiasb.com);
 */
public class KVUtil {
    public static final RecordIterator<KeyValueEntry> EMPTY_ITERATOR = new RecordIterator<KeyValueEntry>() {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public KeyValueEntry next() {
            throw new NoSuchElementException();
        }

        @Override
        public void close() {
            
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    };

    public static EntryList getSlice(OrderedKeyValueStore store, StaticBuffer keyStart, StaticBuffer keyEnd, StoreTransaction txh) throws StorageException {
        return convert(store.getSlice(keyStart, keyEnd, KeySelector.SelectAll, txh));
    }

    public static EntryList getSlice(OrderedKeyValueStore store, StaticBuffer keyStart, StaticBuffer keyEnd, int limit, StoreTransaction txh) throws StorageException {
        return convert(store.getSlice(keyStart, keyEnd, new LimitedSelector(limit), txh));
    }

    public static EntryList convert(RecordIterator<KeyValueEntry> iter) throws StorageException {
        try {
            return StaticArrayEntryList.ofStaticBuffer(iter, KVEntryGetter.INSTANCE);
        } finally {
            try {
                iter.close();
            } catch (IOException e) {
                throw new TemporaryStorageException(e);
            }
        }
    }

    private static enum KVEntryGetter implements StaticArrayEntry.GetColVal<KeyValueEntry,StaticBuffer> {
        INSTANCE;

        @Override
        public StaticBuffer getColumn(KeyValueEntry element) {
            return element.getKey();
        }

        @Override
        public StaticBuffer getValue(KeyValueEntry element) {
            return element.getValue();
        }
    };

    public static class RangeKeySelector implements KeySelector {

        private final StaticBuffer lower; //inclusive
        private final StaticBuffer upper; //exclusive

        public RangeKeySelector(StaticBuffer lower, StaticBuffer upper) {
            this.lower = lower;
            this.upper = upper;
        }

        @Override
        public boolean include(StaticBuffer key) {
            return lower.compareTo(key) <= 0 && upper.compareTo(key) > 0;
        }

        @Override
        public boolean reachedLimit() {
            return false;
        }
    }
}
