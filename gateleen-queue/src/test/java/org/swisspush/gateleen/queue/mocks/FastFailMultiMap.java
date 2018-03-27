package org.swisspush.gateleen.queue.mocks;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.MultiMap;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * A simple multimap throwing {@link UnsupportedOperationException}
 * no mather which method got called.
 *
 * Simply override methods you need for your tests.
 */
public class FastFailMultiMap implements MultiMap {

    private static final String msg = "Method not implemented. Override method to mock your behaviour.";

    @Override
    public String get(CharSequence name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public @Nullable String get(String name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public List<String> getAll(String name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public List<String> getAll(CharSequence name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public List<Map.Entry<String, String>> entries() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean contains(String name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean contains(CharSequence name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public Set<String> names() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap add(String name, String value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap add(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap add(String name, Iterable<String> values) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap add(CharSequence name, Iterable<CharSequence> values) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap addAll(MultiMap map) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap addAll(Map<String, String> headers) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap set(String name, String value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap set(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap set(String name, Iterable<String> values) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap set(CharSequence name, Iterable<CharSequence> values) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap setAll(MultiMap map) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap setAll(Map<String, String> headers) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap remove(String name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap remove(CharSequence name) {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public MultiMap clear() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException( msg );
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        throw new UnsupportedOperationException( msg );
    }
}
