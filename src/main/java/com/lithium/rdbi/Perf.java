package com.lithium.rdbi;

import com.lithium.rdbi.recipes.scheduler.ExclusiveJobScheduler;
import org.joda.time.Instant;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Set;

public class Perf {

    public static void main(String[] args) throws InterruptedException {

        RDBI rdbi = new RDBI(new JedisPool("localhost"));

        final ExclusiveJobScheduler scheduledJobSystem = new ExclusiveJobScheduler(rdbi, "myprefix:");

        for ( int i = 0; i < 100; i++) {
            scheduledJobSystem.schedule("mytube", "{hello:world} " + i, 0);
        }
        for ( int i = 0; i < 100; i++) {
            scheduledJobSystem.reserve("mytube", 1);
        }
        JedisHandle handle = rdbi.open();

        try {
            Jedis jedis = handle.jedis();
            Set<String> results = jedis.keys("*");
            jedis.del((String[]) results.toArray(new String[0]));
        } finally {
            handle.close();
        }

        Instant before = new Instant();

        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                for ( int i = 0; i < 3333; i++) {
                    scheduledJobSystem.schedule("mytube", "{hello:world} " + i, 0);
                }
            }
        });

        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                for ( int i = 0; i < 3333; i++) {
                    scheduledJobSystem.schedule("mytube", "{hello:world} " + i, 0);
                }
            }
        });

        Thread t3 = new Thread(new Runnable() {
            @Override
            public void run() {
                for ( int i = 0; i < 3333; i++) {
                    scheduledJobSystem.schedule("mytube", "{hello:world} " + i, 0);
                }
            }
        });


        t1.start();
        t2.start();
        t3.start();

        t3.join();
        t1.join();
        t2.join();

        Instant after = new Instant();

        Thread.sleep(2000);

        System.out.println("final " + after.minus(before.getMillis()).getMillis());

        Instant before2 = new Instant();
        for ( int i = 0; i < 10000; i++) {
            scheduledJobSystem.reserve("mytube", 1);
        }

        Instant after2 = new Instant();
        System.out.println("final " + after2.minus(before2.getMillis()).getMillis());
    }
}