package com.lithium.dbi.rdbi.recipes.scheduler;

import com.lithium.dbi.rdbi.Handle;
import com.lithium.dbi.rdbi.RDBI;

import java.util.List;

/**
 * Implements a scheduled job system that de-duplicates jobs. A job system consists of one or more tubes. Each tube two
 * queue, a ready queue and a running queue. To shedule a job on the ready queue, use schedule( jobStr, ttlInMillis). When the
 * job is ready, you can reserve the job to run via reserve(ttrInMillis). When a job is reserved it has a count down of
 * ttr, once ttr is up, the job is considered expired. Expired jobs can be extracted from the queue via cull(). To cancel
 * a job in the running or ready queue, use delete().
 *
 * TODO add tutorial, remember to add attribution to the idea
 * https://github.com/kr/beanstalkd/blob/master/doc/protocol.txt
 */
public class ExclusiveJobScheduler extends AbstractDedupJobScheduler {

    /**
     * @param rdbi the rdbi driver
     * @param redisPrefixKey the prefix key for the job system. All keys the job system uses will have the prefix redisPrefixKey
     */
    public ExclusiveJobScheduler(RDBI rdbi, String redisPrefixKey) {
        super(rdbi, redisPrefixKey, System::currentTimeMillis);
    }

    //TODO think about runtime exception, the scheduler should catch all connection based one and handle them

    @Override
    public boolean schedule(final String tube, final String jobStr, final int becomeReadyInMillis) {
        try (Handle handle = rdbi.open()) {
            return 1 == handle.attach(ExclusiveJobSchedulerDAO.class).scheduleJob(
                    getReadyQueue(tube),
                    getRunningQueue(tube),
                    getPaused(tube),
                    jobStr,
                    getClock().getAsLong() + becomeReadyInMillis);
        }
    }

    @Override
    public List<TimeJobInfo> reserveMulti(final String tube, final long considerExpiredAfterMillis, final int maxNumberOfJobs) {
        try (Handle handle = rdbi.open()) {
            return handle.attach(ExclusiveJobSchedulerDAO.class).reserveJobs(
                    getReadyQueue(tube),
                    getRunningQueue(tube),
                    getPaused(tube),
                    maxNumberOfJobs,
                    getClock().getAsLong(),
                    getClock().getAsLong() + considerExpiredAfterMillis);
        }
    }


    @Override
    public boolean deleteJob(final String tube, String jobStr) {
        try (Handle handle = rdbi.open()) {
            return 1 == handle.attach(ExclusiveJobSchedulerDAO.class)
                              .deleteJob(getReadyQueue(tube), getRunningQueue(tube), jobStr);
        }
    }

    public List<TimeJobInfo> removeExpiredJobs(String tube) {
        try (Handle handle = rdbi.open()) {
            return handle.attach(ExclusiveJobSchedulerDAO.class)
                         .removeExpiredJobs(getRunningQueue(tube), getClock().getAsLong());
        }
    }

    /**
     * A job that has been sitting in the ready queue for the last "expirationPeriodInMillis"
     * is considered expired. Expired jobs are removed from the scheduler and returned to the client.
     * @param tube the name of a tube for related jobs
     * @param expirationPeriodInMillis the age in milliseconds beyond which a job is considered expired.
     * @return list of removed jobs
     */
    public List<TimeJobInfo> removeExpiredReadyJobs(String tube, long expirationPeriodInMillis) {
        try (Handle handle = rdbi.open()) {
            return handle.attach(ExclusiveJobSchedulerDAO.class)
                         .removeExpiredJobs(getReadyQueue(tube), getClock().getAsLong() - expirationPeriodInMillis);
        }
    }
}
