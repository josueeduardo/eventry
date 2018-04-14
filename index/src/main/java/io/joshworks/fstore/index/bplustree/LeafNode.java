package io.joshworks.fstore.index.bplustree;

import io.joshworks.fstore.index.Entry;
import io.joshworks.fstore.index.bplustree.storage.BlockStore;
import io.joshworks.fstore.index.bplustree.util.DeleteResult;
import io.joshworks.fstore.index.bplustree.util.InsertResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LeafNode<K extends Comparable<K>, V> extends Node<K, V> {

    final List<V> values;
    int next = -1;
    int previous; //TODO


    protected LeafNode(int order, BlockStore<K, V> store) {
        super(Node.LEAF_NODE, order, store);
        //It's actually order - 1, but to avoid resizing on overflow we leave with one more
        this.values = new ArrayList<>(order);
        this.store = store;
    }

    public int next() {
        return next;
    }

    @Override
    V getValue(K key) {
        int loc = Collections.binarySearch(keys, key);
        return loc >= 0 ? values.get(loc) : null;
    }

    @Override
    DeleteResult<V> deleteValue(K key, Node<K, V> root) {
        int loc = Collections.binarySearch(keys, key);
        DeleteResult<V> deleteResult = new DeleteResult<>();
        if (loc >= 0) {
            keys.remove(loc);
            V removed = values.remove(loc);
            deleteResult.deleted(removed);

            //DIRTY
            store.writeBlock(this);

        }
        return deleteResult;
    }

    @Override
    InsertResult<V> insertValue(K key, V value, Node<K, V> root) {
        int loc = Collections.binarySearch(keys, key);
        int valueIndex = loc >= 0 ? loc : -loc - 1;

        InsertResult<V> insertResult = new InsertResult<>();

        //TODO check duplicated values
        if (loc >= 0) {
            V v = values.get(valueIndex);
            insertResult.previousValue(v);
            values.set(valueIndex, value);
        } else {
            keys.add(valueIndex, key);
            values.add(valueIndex, value);
        }

        //DIRTY
        store.writeBlock(this);

        if (root.isOverflow()) {
            Node<K, V> sibling = split();
            InternalNode<K, V> newRoot = new InternalNode<>(order, store);
            newRoot.keys.add(sibling.getFirstEntry().key);
            newRoot.children.add(this.id());
            newRoot.children.add(sibling.id());
            //root = newRoot;
            int id = store.placeBlock(newRoot);
            return insertResult.newRootId(id);
        }
        return insertResult;
    }


    @Override
    void merge(Node<K, V> sibling) {
        LeafNode<K, V> node = (LeafNode<K, V>) sibling;
        keys.addAll(node.keys);
        values.addAll(node.values);
        next = node.next;

        //DIRTY
        store.writeBlock(this);
        store.freeBlock(sibling.id());
    }

    @Override
    Node<K, V> split() {
        //TODO change BlockStore to accept an already populated block and no id, the method stores the new block, set the id and return the id
        LeafNode<K, V> sibling = new LeafNode<>(order, store);
        int from = (keyNumber() + 1) / 2, to = keyNumber();
        sibling.keys.addAll(keys.subList(from, to));
        sibling.values.addAll(values.subList(from, to));

        keys.subList(from, to).clear();
        values.subList(from, to).clear();

        sibling.next = next;
        next = store.placeBlock(sibling);

        //DIRTY
        store.writeBlock(this);

        return sibling;
    }

    @Override
    boolean isOverflow() {
        return values.size() > order - 1;
    }

    @Override
    boolean isUnderflow() {
        return values.size() > order - 1;
    }

    @Override
    Entry<K, V> getFirstEntry() {
        return Entry.of(keys.get(0), values.get(0));
    }

    public List<Entry<K, V>> entries() {
        List<Entry<K, V>> entries = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            entries.add(Entry.of(keys.get(i), values.get(i)));
        }
        return entries;
    }
}