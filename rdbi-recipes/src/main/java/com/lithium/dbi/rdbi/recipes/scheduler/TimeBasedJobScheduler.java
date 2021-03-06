package com.lithium.dbi.rdbi.recipes.scheduler;

import com.lithium.dbi.rdbi.Handle;
import com.lithium.dbi.rdbi.RDBI;
import redis.clients.jedis.Pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Differences between this class and {@link com.lithium.dbi.rdbi.recipes.scheduler.ExclusiveJobScheduler}:
 * <ul>
 *     <li>Repeatedly calling schedule will succeed each time, merely updating the score in Redis.</li>
 *     <li>The delete operation does not delete from the readyQueue, but only the runningQueue.</li>
 *     <li>Expired jobs in the runningQueue are not purged, but instead moved back to the readyQueue.</li>
 * </ul>
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class TimeBasedJobScheduler extends AbstractJobScheduler<TimeJobInfo> {

    /**
     * @param rdbi the rdbi driver
     * @param redisPrefixKey the prefix key for the job system. All keys the job system uses will have the prefix redisPrefixKey
     */
    @SuppressWarnings("WeakerAccess")
    public TimeBasedJobScheduler(RDBI rdbi, String redisPrefixKey) {
        super(rdbi, redisPrefixKey);
    }

    /**
     * Add a new job to the TimeBasedJobScheduler
     *
     * @param tube Used in conjunction with the redisPrefixKey (constructor) to make up the full redis key name.
     * @param jobStr A string representation of the job to be scheduled
     * @param millisInFuture The "priority" of the job in terms of the number of millis in the future that this job should become available.
     * @param quiescence The maximum forward distance (exclusive) in millis from current timestamp to allow an update if the job already exists.
     * @return true if the job was newly scheduled or updated, or false if the job already existed and was outside of its quiescence period
     */
    public boolean schedule(final String tube, final String jobStr, final long millisInFuture, final long quiescence) {
        return schedule(tube, jobStr, millisInFuture, quiescence, Optional.empty());
    }

    /**
     * Add a new job to the TimeBasedJobScheduler
     *
     * @param tube Used in conjunction with the redisPrefixKey (constructor) to make up the full redis key name.
     * @param jobStr A string representation of the job to be scheduled
     * @param millisInFuture The "priority" of the job in terms of the number of millis in the future that this job should become available.
     * @param quiescence The maximum forward distance (exclusive) in millis from current timestamp to allow an update if the job already exists.
     * @param maxReadyQueueSize The maximum allowed size of the ready queue, or empty if it should be unbounded.
     * @return true if the job was newly scheduled or updated, or false if the job already existed and was outside of its quiescence period
     */
    public boolean schedule(final String tube, final String jobStr, final long millisInFuture, final long quiescence, final Optional<Long> maxReadyQueueSize) {
        try (Handle handle = rdbi.open()) {
            int scheduleResult = handle.attach(JobSchedulerDAO.class)
                                       .scheduleJob(
                                               getReadyQueue(tube),
                                               jobStr,
                                               Instant.now().toEpochMilli() + millisInFuture,
                                               quiescence,
                                               maxReadyQueueSize.orElse(-1L));
            if (scheduleResult == -1) {
                throw new MaxSizeExceededException();
            }
            return scheduleResult == 1;
        }
    }

    /**
     * Add multiple jobs to the TimeBasedJobScheduler, using a pipeline command to avoid O(n) network round trips.
     * Note that this implementation does not support quiescence.
     * @see #schedule(String, String, long, long)
     *
     * @param tube Used in conjunction with the redisPrefixKey (constructor) to make up the full redis key name.
     * @param jobStrs String representations of the jobs to be scheduled
     * @param millisInFuture The "priority" of the job in terms of the number of millis in the future that this job should become available.
     * @return number of newly scheduled jobs
     */
    public int scheduleMulti(final String tube, final Iterable<String> jobStrs, final long millisInFuture) {
        try (Handle handle = rdbi.open()) {
            final Pipeline pl = handle.jedis().pipelined();
            for (String jobStr : jobStrs) {
                pl.zadd(getReadyQueue(tube),
                        Instant.now().toEpochMilli() + millisInFuture,
                        jobStr);
            }
            int numAdded = 0;
            for (Object rslt : pl.syncAndReturnAll()) {
                numAdded += (Long) rslt;
            }
            return numAdded;
        }
    }

    /**
     * Reserve multiple jobs at once.
     *
     * @param tube Used in conjunction with the redisPrefixKey (constructor) to make up the full redis key name.
     * @param timeToReserveMillis The duration of the reservation in milliseconds, after which time the jobs become available to be put back into the ready queue again if not deleted first.
     * @param maxNumberOfJobs The maximum number of jobs to reserve
     * @return The list of jobs that have been reserved
     */
    @Override
    public List<TimeJobInfo> reserveMulti(final String tube, final long timeToReserveMillis, final int maxNumberOfJobs) {
        try (Handle handle = rdbi.open()) {
            final List<JobInfo> jobInfos =
                    handle.attach(JobSchedulerDAO.class)
                          .reserveJobs(
                                  getReadyQueue(tube),
                                  getRunningQueue(tube),
                                  maxNumberOfJobs,
                                  0L,
                                  Instant.now().toEpochMilli(),
                                  Instant.now().toEpochMilli() + timeToReserveMillis);
            return TimeJobInfo.from(jobInfos);
        }
    }

    /**
     * Move any jobs whose reservations have expired back into the ready queue.
     *
     * @param tube Used in conjunction with the redisPrefixKey (constructor) to make up the full redis key name.
     * @return The list of jobs that had expired and that have been already moved back into the ready queue.
     */
    public List<TimeJobInfo> requeueExpired(final String tube) {
        try (Handle handle = rdbi.open()) {
            final List<JobInfo> jobInfos =
                    handle.attach(JobSchedulerDAO.class)
                          .requeueExpiredJobs(getReadyQueue(tube),
                                              getRunningQueue(tube),
                                              Instant.now().toEpochMilli(),
                                              Instant.now().toEpochMilli());
            return TimeJobInfo.from(jobInfos);
        }
    }

    /**
     * Get the current number of jobs that have been reserved.
     *
     * @param tube Used in conjunction with the redisPrefixKey (constructor) to make up the full redis key name.
     * @return The number of reserved jobs
     */
    public long runningSize(String tube) {
        return getSortedSetSize(getRunningQueue(tube));
    }

    /**
     * Get the current number of jobs available to be reserved.
     *
     * @param tube Used in conjunction with the redisPrefixKey (constructor) to make up the full redis key name.
     * @return The number of available (ready) jobs to be reserved
     */
    public long readySize(String tube) {
        return getSortedSetSize(getReadyQueue(tube));
    }


    public List<TimeJobInfo> peekDelayed(String tube, int offset, int count) {
        return peekInternal(getReadyQueue(tube), (double) Instant.now().toEpochMilli(), Double.MAX_VALUE, offset, count);
    }

    public List<TimeJobInfo> peekReady(String tube, int offset, int count) {
        return peekInternal(getReadyQueue(tube), 0.0d, (double) Instant.now().toEpochMilli(), offset, count);
    }

    public List<TimeJobInfo> peekRunning(String tube, int offset, int count) {
        return peekInternal(getRunningQueue(tube), (double) Instant.now().toEpochMilli(), Double.MAX_VALUE, offset, count);
    }

    public List<TimeJobInfo> peekExpired(String tube, int offset, int count) {
        return peekInternal(getRunningQueue(tube), 0.0d, (double) Instant.now().toEpochMilli(), offset, count);
    }

    protected String getRunningQueue(String tube) {
        return prefix + tube + ":running_queue";
    }

    protected String getReadyQueue(String tube) {
        return prefix + tube + ":ready_queue";
    }

    private long getSortedSetSize(final String key) {
        return rdbi.withHandle(handle -> handle.jedis().zcard(key));
    }

    @Override
    protected TimeJobInfo createJobInfo(String jobStr, double jobScore) {
        return new TimeJobInfo(jobStr, jobScore);
    }

}
