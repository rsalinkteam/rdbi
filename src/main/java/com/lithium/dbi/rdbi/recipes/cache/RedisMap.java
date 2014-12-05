package com.lithium.dbi.rdbi.recipes.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.lithium.dbi.rdbi.Handle;
import com.lithium.dbi.rdbi.RDBI;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class RedisMap<KeyType, ValueType> implements Map<KeyType, ValueType> {
    private static final Logger log = LoggerFactory.getLogger(RDBI.class);
    private final KeyGenerator<KeyType> redisKeyGenerator;
    private final SerializationHelper<ValueType> serializationHelper;
    private final RDBI rdbi;
    private final String cacheName;
    private final String keyPrefix;
    private final int valueTtl;


    /**
     * @param keyGenerator - something that will turn your key object into a string redis can use as a key.
     *                     will be prefixed by the keyPrefix string.
     * @param serializationHelper - a codec to get your value object to and from a string
     * @param rdbi
     * @param cacheName - name of cache, used in log statements
     * @param keyPrefix - prefix of all keys used by this cache in redis
     * @param valueTtlSecs - redis entries holding your values will expire after this many seconds
     */
    public RedisMap(KeyGenerator<KeyType> redisKeyGenerator,
                    SerializationHelper<ValueType> serializationHelper,
                    RDBI rdbi,
                    String cacheName,
                    String keyPrefix,
                    Duration valueTtl) {
        this.redisKeyGenerator = redisKeyGenerator;
        this.serializationHelper = serializationHelper;
        this.rdbi = rdbi;
        this.cacheName = cacheName;
        this.keyPrefix = keyPrefix;
        if (valueTtl.getStandardSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Duration outside of valid Jedis/redis expiry range.");
        }
        this.valueTtl = (int)valueTtl.getStandardSeconds();
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException("Not supported by this redis map implementation.");
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException("Not supported by this redis map implementation.");
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        throw new UnsupportedOperationException("containsValue not supported for this cache!");
    }

    @Override
    public ValueType get(Object key) {
        final String redisKey = generateRedisKey(turnObjectIntoKeyType(key));
        final Response<String> valAsString;
        try (final Handle handle = rdbi.open()) {
            final Pipeline pl = handle.jedis().pipelined();
            valAsString = pl.get(redisKey);
            pl.expire(redisKey, valueTtl);
            pl.sync();
        }
        return getFromResponse(valAsString);
    }

    @Override
    public ValueType put(KeyType key, ValueType value) {
        final Response<String> oldVal;
        try (final Handle handle = rdbi.open()) {
            final Pipeline pl = handle.jedis().pipelined();
            oldVal = put(key, value, pl);
            pl.sync();

        }
        return getFromResponse(oldVal);
    }

    protected ValueType getFromResponse(Response<String> response) {
        if (response != null && !Strings.isNullOrEmpty(response.get())) {
            return serializationHelper.decode(response.get());
        } else {
            return null;
        }
    }

    @VisibleForTesting
    protected Response<String> put(KeyType key, ValueType value, Pipeline pipeline) {
        final String redisKey = generateRedisKey(key);
        final String valueAsString = serializationHelper.encode(value);
        final Response<String> currVal = pipeline.get(redisKey);
        pipeline.setex(redisKey, this.valueTtl, valueAsString);
        return currVal;
    }

    @Override
    public ValueType remove(Object key) {
        final String redisKey = generateRedisKey(turnObjectIntoKeyType(key));
        final ValueType val = get(key);
        try (final Handle handle = rdbi.open()) {
            handle.jedis().del(redisKey);
        }
        return val;
    }

    @Override
    public void putAll(Map<? extends KeyType, ? extends ValueType> m) {
        try(final Handle handle = rdbi.open()) {
            final Pipeline pl = handle.jedis().pipelined();
            for (final Entry<? extends KeyType, ? extends ValueType> entry : m.entrySet()) {
                put(entry.getKey(), entry.getValue(), pl);
            }
            pl.sync();
        }
    }

    @Override
    public void clear() {

    }

    @Override
    public Set<KeyType> keySet() {
        throw new UnsupportedOperationException("Not supported by this redis map implementation.");
    }

    @Override
    public Collection<ValueType> values() {
        throw new UnsupportedOperationException("Not supported by this redis map implementation.");
    }

    @Override
    public Set<Entry<KeyType, ValueType>> entrySet() {
        throw new UnsupportedOperationException("Not supported by this redis map implementation.");
    }

    @VisibleForTesting
    protected String generateRedisKey(KeyType key) {
        return keyPrefix + redisKeyGenerator.redisKey(key);
    }

    @VisibleForTesting
    protected KeyType turnObjectIntoKeyType(final Object obj) {
        try {
            return (KeyType)obj;
        } catch (ClassCastException ex) {
            throw ex;
        }
    }
}