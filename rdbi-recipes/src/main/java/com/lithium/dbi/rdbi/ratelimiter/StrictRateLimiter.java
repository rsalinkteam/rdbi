package com.lithium.dbi.rdbi.ratelimiter;

import com.google.common.base.Joiner;
import com.lithium.dbi.rdbi.Handle;
import com.lithium.dbi.rdbi.RDBI;
import org.apache.commons.codec.digest.DigestUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.OptionalLong;

/**
 * Rate limiter implementation that will never allow
 * greater than the configured requests/second
 */
public class StrictRateLimiter implements RateLimiter {

    private static final String LUA_SCRIPT = Joiner.on("\n").join(
                                                        "local keyName = KEYS[1]",
                                                        "local expireAfterSeconds = tonumber(ARGV[1])",
                                                        "local allowedPermits = tonumber(ARGV[2])",

                                                        "local currentCounterValue = redis.call('GET', keyName)",
                                                        "if not currentCounterValue then",
                                                        "   currentCounterValue = '0'",
                                                        "   redis.call('SET', keyName, currentCounterValue, 'nx', 'ex', expireAfterSeconds)",
                                                        "elseif redis.call('TTL', keyName) < 0 then",
                                                        "   redis.call('EXPIRE', keyName, expireAfterSeconds)",
                                                        "end",

                                                        "if tonumber(currentCounterValue) < allowedPermits then",
                                                        "   return redis.call('INCR', keyName)",
                                                        "end",
                                                        "return -1 * redis.call('TTL', keyName)");
    private static final String LUA_SCRIPT_SHA1 = DigestUtils.sha1Hex(LUA_SCRIPT);

    private final RDBI rdbi;
    private final String fullyQualifiedKey;
    private final int keyExpiresAfterSeconds;
    private final int allowedPermitsPerExpirationCycle;

    public StrictRateLimiter(String keyPrefix, RDBI rdbi, String key, double permitsPerSecond) {
        this.rdbi = rdbi;

        fullyQualifiedKey = Joiner.on(":").join(keyPrefix, "ratelimit", key);

        if (permitsPerSecond < 1) {
            // We want less than one permit per second, but redis can only expire a key at second boundaries.
            // Example:
            //      permitsPerSecond = 0.53333   (160 requests every 300 sec)
            //      secondsBetweenRequests = 1.875
            double secondsBetweenRequests = 1 / permitsPerSecond;

            // Let's round up to provide the most conservative behavior
            // Example:
            //      keyExpiresAfterSeconds = 2
            keyExpiresAfterSeconds = Long.valueOf(Math.round(secondsBetweenRequests + 0.5)).intValue();
            allowedPermitsPerExpirationCycle = 1;
        } else {
            keyExpiresAfterSeconds = 1;
            allowedPermitsPerExpirationCycle = Long.valueOf(Math.round(permitsPerSecond)).intValue();
        }
    }

    @Override
    public OptionalLong getWaitTimeForPermit() {
        try (Handle handle = rdbi.open()) {
            final Jedis jedis = handle.jedis();

            while (true) {
                long evalResult;
                try {
                    // Use the sha to run the LUA script for maximum performance.  Have a fall-back to reload the script
                    // in the event of a redis master/slave failover or upon initial execution.
                    evalResult = (Long) jedis.evalsha(LUA_SCRIPT_SHA1, 1, fullyQualifiedKey, String.valueOf(keyExpiresAfterSeconds), String.valueOf(allowedPermitsPerExpirationCycle));
                } catch (JedisDataException jde) {
                    if (jde.getMessage() != null && jde.getMessage().contains("NOSCRIPT")) {
                        jedis.scriptLoad(LUA_SCRIPT);
                        continue;
                    } else {
                        throw jde;
                    }
                }

                if (evalResult > 0) {
                    // We are good!
                    return OptionalLong.empty();
                }

                // We are over our allotment. The return value is the negative of the number of seconds we should wait.
                long retryInMillis = -1 * evalResult * 1000;
                return OptionalLong.of(retryInMillis);
            }
        }
    }

    @Override
    public String getKey() {
        return fullyQualifiedKey;
    }

}
