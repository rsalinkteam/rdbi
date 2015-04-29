package com.lithium.dbi.rdbi.recipes.cache;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.lithium.dbi.rdbi.RDBI;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import redis.clients.jedis.JedisPool;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

@Test(groups = "integration")
public class RedisHashCacheTest {

    private ExecutorService es;

    @Test
    public void sniffTest() throws ExecutionException {
        final String key1 = "key1";
        final TestContainer tc1 = new TestContainer(key1, UUID.randomUUID());
        final String key2 = "key2";
        final TestContainer tc2 = new TestContainer(key2, UUID.randomUUID());
        final String barfKey = "barf";

        final ImmutableMap<String, TestContainer> mappings = ImmutableMap.of(key1, tc1, key2, tc2);
        final Function<String, TestContainer> loader = new Function<String, TestContainer>() {
            @Nullable
            @Override
            public TestContainer apply(@Nullable String s) {
                if (barfKey.equals(s)) {
                    throw new RuntimeException(barfKey);
                }
                return mappings.get(s);
            }
        };

        final Callable<Collection<TestContainer>> loadAll = new Callable<Collection<TestContainer>>() {
            @Override
            public Collection<TestContainer> call() throws Exception {
                return mappings.values();
            }
        };

        final RDBI rdbi = createRdbi();

        final CounterRunnable hits = new CounterRunnable();
        final CounterRunnable misses = new CounterRunnable();
        final CounterRunnable loadSuccess = new CounterRunnable();
        final CounterRunnable loadFailure = new CounterRunnable();

        final RedisHashCache<String, TestContainer> cache =
                new RedisHashCache<>(
                        keyGenerator,
                        keyCodec,
                        valueGenerator,
                        helper,
                        rdbi,
                        loader,
                        loadAll,
                        "superFancyCache",
                        "prefix",
                        120,
                        0,
                        Optional.of(es),
                        hits,
                        misses,
                        loadSuccess,
                        loadFailure);

        // Clear out any potential preexisting data.
        cache.invalidateAll();

        assertEquals(tc1.getUuid(), cache.get(key1).getUuid());
        assertEquals(1, misses.get());
        assertEquals(0, hits.get());
        assertEquals(1, loadSuccess.get());
        assertEquals(0, loadFailure.get());
        assertEquals(tc1.getUuid(), cache.get(key1).getUuid());
        assertEquals(1, misses.get());
        assertEquals(1, hits.get());
        assertEquals(1, loadSuccess.get());
        assertEquals(0, loadFailure.get());

        assertNull(cache.get("goobagobbafake"));
        assertEquals(2, misses.get());
        assertEquals(1, hits.get());
        assertEquals(1, loadSuccess.get());
        assertEquals(1, loadFailure.get());


        boolean thrown = false;
        try {
            cache.get(barfKey);
        } catch (ExecutionException ex) {
            thrown = true;
            assertEquals(barfKey, ex.getCause().getMessage());
        }
        assertTrue(thrown);
        assertEquals(3, misses.get());
        assertEquals(1, hits.get());
        assertEquals(1, loadSuccess.get());
        assertEquals(2, loadFailure.get());

        thrown = false;
        try {
            cache.getUnchecked(barfKey);
        } catch (Exception ex) {
            thrown = true;
            assertEquals(barfKey, ex.getMessage());
        }
        assertTrue(thrown);
        assertEquals(4, misses.get());
        assertEquals(1, hits.get());
        assertEquals(1, loadSuccess.get());
        assertEquals(3, loadFailure.get());
    }

    @Test
    public void sniffTest2() throws ExecutionException, InterruptedException {
        final String key1 = "key1";
        final TestContainer tc1 = new TestContainer(key1, UUID.randomUUID());

        final String key2 = "key2";
        final TestContainer tc2 = new TestContainer(key2, UUID.randomUUID());

        final String key3 = "key3";
        final TestContainer tc3 = new TestContainer(key3, UUID.randomUUID());

        final Map<String, TestContainer> dataSource = ImmutableMap.of(key1, tc1, key2, tc2, key3, tc3);

        final Function<String, TestContainer> loader = new Function<String, TestContainer>() {
            @Override
            public TestContainer apply(@Nullable String s) {
               return dataSource.get(s);
            }
        };

        final Callable<Collection<TestContainer>> loadAll = new Callable<Collection<TestContainer>>() {
            @Override
            public Collection<TestContainer> call() throws Exception {
                return dataSource.values();
            }
        };

        final RDBI rdbi = createRdbi();

        final RedisHashCache<String, TestContainer> cache =
                new RedisHashCache<>(
                        keyGenerator,
                        keyCodec,
                        valueGenerator,
                        helper,
                        rdbi,
                        loader,
                        loadAll,
                        "superFancyCache",
                        "prefix",
                        120,
                        0,
                        Optional.<ExecutorService>absent(), // Force synchronous calls!
                        NOOP,
                        NOOP,
                        NOOP,
                        NOOP);

        // Clear out any potential preexisting data.
        cache.invalidateAll();

        assertEquals(0, cache.size());
        Collection<TestContainer> refreshedResults = cache.refreshAll().get().getOrThrowUnchecked();
        assertEquals(3, refreshedResults.size());

        assertEquals(3, cache.size());

        cache.invalidate(key1);
        assertEquals(ImmutableSet.of(key1), cache.getMissing());

        // key1/tc1 should no longer be in the cache and getAllPresent does not produce it
        Map<String, TestContainer> dataInRedis = cache.getAllPresent(ImmutableList.of(key1, key2, key3));
        assertEquals(2, dataInRedis.size());
        assertFalse(dataInRedis.containsKey(key1));
        assertTrue(dataInRedis.containsKey(key2));
        assertTrue(dataInRedis.containsKey(key3));
        // Size should include the "missing" data for key1
        assertEquals(3, cache.size());
        // Getting key1 should load the value from the data source
        assertEquals(tc1, cache.get(key1));
        // The missing set should have been cleared out
        assertEquals(Collections.emptySet(), cache.getMissing());

        // Invalidate key2
        cache.invalidate(key2);
        // Missing should contain key2
        assertEquals(ImmutableSet.of(key2), cache.getMissing());
        // key1/tc1 should no longer be in the cache but getAll should produce it
        Map<String, TestContainer> dataInRedis2 = cache.getAll(ImmutableList.of(key1, key2, key3));
        assertEquals(3, dataInRedis2.size());
        assertTrue(dataInRedis2.containsKey(key1));
        assertTrue(dataInRedis2.containsKey(key2));
        assertTrue(dataInRedis2.containsKey(key3));
        // Missing should now be empty
        assertEquals(Collections.emptySet(), cache.getMissing());

        // Re-invalidate key3
        cache.invalidate(key3);
        // Missing should contain key3
        assertEquals(ImmutableSet.of(key3), cache.getMissing());
        // key1/tc1 should no longer be in the cache but asMap should produce it
        Map<String, TestContainer> dataInRedis3 = cache.asMap();
        assertEquals(3, dataInRedis3.size());
        assertTrue(dataInRedis3.containsKey(key1));
        assertTrue(dataInRedis3.containsKey(key2));
        assertTrue(dataInRedis3.containsKey(key3));
        // Missing should now be empty
        assertEquals(Collections.emptySet(), cache.getMissing());
    }

    @Test
    public void getAllPresentWorksWithDuplicateKeys() {
        final String key1 = "key1";
        final TestContainer tc1 = new TestContainer(key1, UUID.randomUUID());

        final String key2 = "key2";
        final TestContainer tc2 = new TestContainer(key2, UUID.randomUUID());

        final String key3 = "key3";
        final TestContainer tc3 = new TestContainer(key3, UUID.randomUUID());

        final Map<String, TestContainer> dataSource = ImmutableMap.of(key1, tc1, key2, tc2, key3, tc3);

        final Function<String, TestContainer> loader = new Function<String, TestContainer>() {
            @Override
            public TestContainer apply(@Nullable String s) {
                return dataSource.get(s);
            }
        };

        final Callable<Collection<TestContainer>> loadAll = new Callable<Collection<TestContainer>>() {
            @Override
            public Collection<TestContainer> call() throws Exception {
                return dataSource.values();
            }
        };

        final RDBI rdbi = createRdbi();

        final RedisHashCache<String, TestContainer> cache =
                new RedisHashCache<>(
                        keyGenerator,
                        keyCodec,
                        valueGenerator,
                        helper,
                        rdbi,
                        loader,
                        loadAll,
                        "superFancyCache",
                        "prefix",
                        120,
                        0,
                        Optional.<ExecutorService>absent(), // Force synchronous calls!
                        NOOP,
                        NOOP,
                        NOOP,
                        NOOP);

        // Clear out any potential preexisting data.
        cache.invalidateAll();

        assertEquals(0, cache.size());
        cache.refreshAll();

        Map<String, TestContainer> results = cache.getAllPresent(ImmutableList.of(key1, key1, key2, key3));
        assertTrue(results.containsKey(key1));
        assertEquals(tc1, results.get(key1));

        assertTrue(results.containsKey(key2));
        assertEquals(tc2, results.get(key2));

        assertTrue(results.containsKey(key3));
        assertEquals(tc3, results.get(key3));
    }

    @Test
    public void verifyAsyncitude() throws InterruptedException, ExecutionException {
        // it's a word

        final String key1 = "key1";
        final TestContainer tc1 = new TestContainer(key1, UUID.randomUUID());

        final ArrayBlockingQueue<TestContainer> queue = new ArrayBlockingQueue<>(1);
        final Function<String, TestContainer> mrDeadlock = new Function<String, TestContainer>() {
            @Nullable
            @Override
            public TestContainer apply(@Nullable String s) {
                try {
                    return queue.take();
                } catch (InterruptedException e) {
                    throw Throwables.propagate(e);
                }
            }
        };
        final Callable<Collection<TestContainer>> loadAll = new Callable<Collection<TestContainer>>() {
            @Override
            public Collection<TestContainer> call() throws Exception {
                return Collections.emptyList();
            }
        };

        final RDBI rdbi = createRdbi();

        final CounterRunnable hits = new CounterRunnable();
        final CounterRunnable misses = new CounterRunnable();
        final CounterRunnable loadSuccess = new CounterRunnable();
        final CounterRunnable loadFailure = new CounterRunnable();

        final RedisHashCache<String, TestContainer> cache =
                new RedisHashCache<>(
                        keyGenerator,
                        new PassthroughSerializationHelper(),
                        valueGenerator,
                        helper,
                        rdbi,
                        mrDeadlock,
                        loadAll,
                        "superFancyCache",
                        "prefix",
                        120,
                        0,
                        Optional.of(es),
                        hits,
                        misses,
                        loadSuccess,
                        loadFailure);

        // Clear out any potential preexisting data.
        cache.invalidateAll();

        // this call would block if not executed asynchronously
        cache.refresh(key1);
        queue.put(tc1);
        while (queue.size() > 0 || cache.isLocked()) {
            Thread.sleep(50);
        }
        assertEquals(tc1.getUuid(), cache.get(key1).getUuid());
        assertEquals(0, misses.get());
        assertEquals(1, hits.get());
        assertEquals(1, loadSuccess.get());
        assertEquals(0, loadFailure.get());
        assertEquals(tc1.getUuid(), cache.get(key1).getUuid());
        assertEquals(0, misses.get());
        assertEquals(2, hits.get());
        assertEquals(1, loadSuccess.get());
        assertEquals(0, loadFailure.get());
    }

    RDBI createRdbi() {
        return new RDBI(new JedisPool("localhost"));
    }

    public static class TestContainer {

        private final String key;
        private final UUID uuid;

        public TestContainer(String key, UUID uuid) {
            this.key = key;
            this.uuid = uuid;
        }

        public String getKey() {
            return key;
        }

        public UUID getUuid() {
            return uuid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TestContainer that = (TestContainer) o;

            if (key != null ? !key.equals(that.key) : that.key != null) return false;
            return !(uuid != null ? !uuid.equals(that.uuid) : that.uuid != null);

        }

        @Override
        public int hashCode() {
            int result = key != null ? key.hashCode() : 0;
            result = 31 * result + (uuid != null ? uuid.hashCode() : 0);
            return result;
        }
    }

    public static final Runnable NOOP = new Runnable() {
        @Override
        public void run() {

        }
    };

    public static class CounterRunnable implements Runnable {
        private AtomicLong atomicLong = new AtomicLong();

        @Override
        public void run() {
            atomicLong.getAndIncrement();
        }

        public long get() {
            return atomicLong.get();
        }
    }

    public static final SerializationHelper<TestContainer> helper = new SerializationHelper<TestContainer>() {
        @Override
        public TestContainer decode(String string) {
            String[] data = string.split(",");
            return new TestContainer(data[0], UUID.fromString(data[1]));
        }

        @Override
        public String encode(TestContainer value) {
            return value.getKey() + "," + value.getUuid().toString();
        }
    };

    public static final SerializationHelper<String> keyCodec = new SerializationHelper<String>() {
        @Override
        public String decode(String string) {
            return string.substring("encoded-key:".length());
        }

        @Override
        public String encode(String value) {
            return "encoded-key:" + value;
        }
    };

    public static final Function<String, String> keyGenerator = new Function<String, String>() {
        @Override
        public String apply(String key) {
            return "item-key:" + key;
        }
    };

    public static final Function<TestContainer, String> valueGenerator = new Function<TestContainer, String>() {
        @Override
        public String apply(TestContainer value) {
            return value.getKey();
        }
    };

    @BeforeMethod
    public void setupExecutor() {
        this.es = Executors.newSingleThreadExecutor();
    }

    @AfterMethod
    public void shutdownExecutor() {
        es.shutdownNow();
    }
}