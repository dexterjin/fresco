/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers;

import android.os.SystemClock;
import androidx.annotation.VisibleForTesting;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.instrumentation.FrescoInstrumenter;
import com.facebook.infer.annotation.FalseOnNull;
import com.facebook.infer.annotation.Nullsafe;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Manages jobs so that only one can be executed at a time and no more often than once in <code>
 * mMinimumJobIntervalMs</code> milliseconds.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class JobScheduler {

  static final String QUEUE_TIME_KEY = "queueTime";

  @VisibleForTesting
  static class JobStartExecutorSupplier {

    private static ScheduledExecutorService sJobStarterExecutor;

    static ScheduledExecutorService get() {
      if (sJobStarterExecutor == null) {
        sJobStarterExecutor = Executors.newSingleThreadScheduledExecutor();
      }
      return sJobStarterExecutor;
    }
  }

  public interface JobRunnable {
    void run(EncodedImage encodedImage, @Consumer.Status int status);
  }

  private final Executor mExecutor;
  private final JobRunnable mJobRunnable;
  private final Runnable mDoJobRunnable;
  private final Runnable mSubmitJobRunnable;
  private final int mMinimumJobIntervalMs;

  @VisibleForTesting
  enum JobState {
    IDLE,
    QUEUED,
    RUNNING,
    RUNNING_AND_PENDING
  }

  // job data

  @GuardedBy("this")
  @VisibleForTesting
  @Nullable
  EncodedImage mEncodedImage;

  @GuardedBy("this")
  @VisibleForTesting
  @Consumer.Status
  int mStatus;

  // job state

  @GuardedBy("this")
  @VisibleForTesting
  JobState mJobState;

  @GuardedBy("this")
  @VisibleForTesting
  long mJobSubmitTime;

  @GuardedBy("this")
  @VisibleForTesting
  long mJobStartTime;

  public JobScheduler(Executor executor, JobRunnable jobRunnable, int minimumJobIntervalMs) {
    mExecutor = executor;
    mJobRunnable = jobRunnable;
    mMinimumJobIntervalMs = minimumJobIntervalMs;
    mDoJobRunnable =
        new Runnable() {
          @Override
          public void run() {
            doJob();
          }
        };
    mSubmitJobRunnable =
        new Runnable() {
          @Override
          public void run() {
            submitJob();
          }
        };
    mEncodedImage = null;
    mStatus = 0;
    mJobState = JobState.IDLE;
    mJobSubmitTime = 0;
    mJobStartTime = 0;
  }

  /**
   * Clears the currently set job.
   *
   * <p>In case the currently set job has been scheduled but not started yet, the job won't be
   * executed.
   */
  public void clearJob() {
    EncodedImage oldEncodedImage;
    synchronized (this) {
      oldEncodedImage = mEncodedImage;
      mEncodedImage = null;
      mStatus = 0;
    }
    EncodedImage.closeSafely(oldEncodedImage);
  }

  /**
   * Updates the job.
   *
   * <p>This just updates the job, but it doesn't schedule it. In order to be executed, the job has
   * to be scheduled after being set. In case there was a previous job scheduled that has not yet
   * started, this new job will be executed instead.
   *
   * @return whether the job was successfully updated.
   */
  public boolean updateJob(@Nullable EncodedImage encodedImage, @Consumer.Status int status) {
    if (!shouldProcess(encodedImage, status)) {
      return false;
    }
    EncodedImage oldEncodedImage;
    synchronized (this) {
      oldEncodedImage = mEncodedImage;
      this.mEncodedImage = EncodedImage.cloneOrNull(encodedImage);
      this.mStatus = status;
    }
    EncodedImage.closeSafely(oldEncodedImage);
    return true;
  }

  /**
   * Schedules the currently set job (if any).
   *
   * <p>This method can be called multiple times. It is guaranteed that each job set will be
   * executed no more than once. It is guaranteed that the last job set will be executed, unless the
   * job was cleared first.
   *
   * <p>The job will be scheduled no sooner than <code>minimumJobIntervalMs</code> milliseconds
   * since the last job started.
   *
   * @return true if the job was scheduled, false if there was no valid job to be scheduled
   */
  public boolean scheduleJob() {
    long now = SystemClock.uptimeMillis();
    long when = 0;
    boolean shouldEnqueue = false;
    synchronized (this) {
      if (!shouldProcess(mEncodedImage, mStatus)) {
        return false;
      }
      switch (mJobState) {
        case IDLE:
          when = Math.max(mJobStartTime + mMinimumJobIntervalMs, now);
          shouldEnqueue = true;
          mJobSubmitTime = now;
          mJobState = JobState.QUEUED;
          break;
        case QUEUED:
          // do nothing, the job is already queued
          break;
        case RUNNING:
          mJobState = JobState.RUNNING_AND_PENDING;
          break;
        case RUNNING_AND_PENDING:
          // do nothing, the next job is already pending
          break;
      }
    }
    if (shouldEnqueue) {
      enqueueJob(when - now);
    }
    return true;
  }

  private void enqueueJob(long delay) {
    // If we make mExecutor be a {@link ScheduledexecutorService}, we could just have
    // `mExecutor.schedule(mDoJobRunnable, delay)` and avoid mSubmitJobRunnable and
    // JobStartExecutorSupplier altogether. That would require some refactoring though.
    final Runnable submitJobRunnable =
        FrescoInstrumenter.decorateRunnable(mSubmitJobRunnable, "JobScheduler_enqueueJob");
    if (delay > 0) {
      JobStartExecutorSupplier.get().schedule(submitJobRunnable, delay, TimeUnit.MILLISECONDS);
    } else {
      submitJobRunnable.run();
    }
  }

  private void submitJob() {
    mExecutor.execute(
        FrescoInstrumenter.decorateRunnable(mDoJobRunnable, "JobScheduler_submitJob"));
  }

  private void doJob() {
    long now = SystemClock.uptimeMillis();
    EncodedImage input;
    int status;
    synchronized (this) {
      input = mEncodedImage;
      status = mStatus;
      mEncodedImage = null;
      this.mStatus = 0;
      mJobState = JobState.RUNNING;
      mJobStartTime = now;
    }

    try {
      // we need to do a check in case the job got cleared in the meantime
      if (shouldProcess(input, status)) {
        mJobRunnable.run(input, status);
      }
    } finally {
      EncodedImage.closeSafely(input);
      onJobFinished();
    }
  }

  private void onJobFinished() {
    long now = SystemClock.uptimeMillis();
    long when = 0;
    boolean shouldEnqueue = false;
    synchronized (this) {
      if (mJobState == JobState.RUNNING_AND_PENDING) {
        when = Math.max(mJobStartTime + mMinimumJobIntervalMs, now);
        shouldEnqueue = true;
        mJobSubmitTime = now;
        mJobState = JobState.QUEUED;
      } else {
        mJobState = JobState.IDLE;
      }
    }
    if (shouldEnqueue) {
      enqueueJob(when - now);
    }
  }

  @FalseOnNull
  private static boolean shouldProcess(
      @Nullable EncodedImage encodedImage, @Consumer.Status int status) {
    // the last result should always be processed, whereas
    // an intermediate result should be processed only if valid
    return BaseConsumer.isLast(status)
        || BaseConsumer.statusHasFlag(status, Consumer.IS_PLACEHOLDER)
        || EncodedImage.isValid(encodedImage);
  }

  /**
   * Gets the queued time in milliseconds for the currently running job.
   *
   * <p>The result is only valid if called from {@link JobRunnable#run}.
   */
  public synchronized long getQueuedTime() {
    return mJobStartTime - mJobSubmitTime;
  }
}
