/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.updater;

import java.util.logging.Logger;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.util.Clock;

import org.apache.aurora.gen.Identity;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.storage.entities.ITaskEvent;

import static java.util.Objects.requireNonNull;

import static org.apache.aurora.gen.ScheduleStatus.KILLING;
import static org.apache.aurora.gen.ScheduleStatus.RUNNING;
import static org.apache.aurora.scheduler.updater.StateEvaluator.Result.EVALUATE_AFTER_MIN_RUNNING_MS;
import static org.apache.aurora.scheduler.updater.StateEvaluator.Result.EVALUATE_ON_STATE_CHANGE;
import static org.apache.aurora.scheduler.updater.StateEvaluator.Result.FAILED_STUCK;
import static org.apache.aurora.scheduler.updater.StateEvaluator.Result.FAILED_TERMINATED;
import static org.apache.aurora.scheduler.updater.StateEvaluator.Result.KILL_TASK_AND_EVALUATE_ON_STATE_CHANGE;
import static org.apache.aurora.scheduler.updater.StateEvaluator.Result.REPLACE_TASK_AND_EVALUATE_ON_STATE_CHANGE;
import static org.apache.aurora.scheduler.updater.StateEvaluator.Result.SUCCEEDED;

/**
 * In part of a job update, this manages the update of an individual instance. This includes
 * deciding how to effect an update from a possibly-absent old configuration to a possibly-absent
 * new configuration, and detecting whether a replaced instance becomes unstable.
 */
class InstanceUpdater implements StateEvaluator<Optional<IScheduledTask>> {
  private static final Logger LOG = Logger.getLogger(InstanceUpdater.class.getName());

  private final Optional<ITaskConfig> desiredState;
  private final int toleratedFailures;
  private final Amount<Long, Time> minRunningTime;
  private final Amount<Long, Time> maxNonRunningTime;
  private final Clock clock;

  private int observedFailures = 0;

  InstanceUpdater(
      Optional<ITaskConfig> desiredState,
      int toleratedFailures,
      Amount<Long, Time> minRunningTime,
      Amount<Long, Time> maxNonRunningTime,
      Clock clock) {

    this.desiredState = requireNonNull(desiredState);
    this.toleratedFailures = toleratedFailures;
    this.minRunningTime = requireNonNull(minRunningTime);
    this.maxNonRunningTime = requireNonNull(maxNonRunningTime);
    this.clock = requireNonNull(clock);
  }

  private long millisSince(ITaskEvent event) {
    return clock.nowMillis() - event.getTimestamp();
  }

  private boolean appearsStable(IScheduledTask task) {
    return millisSince(Tasks.getLatestEvent(task)) >= minRunningTime.as(Time.MILLISECONDS);
  }

  private boolean appearsStuck(IScheduledTask task) {
    // Walk task events backwards to find the first event, or first non-running event.
    ITaskEvent earliestNonRunningEvent = task.getTaskEvents().get(0);
    for (ITaskEvent event : Lists.reverse(task.getTaskEvents())) {
      if (event.getStatus() == RUNNING) {
        break;
      } else {
        earliestNonRunningEvent = event;
      }
    }

    return millisSince(earliestNonRunningEvent) >= maxNonRunningTime.as(Time.MILLISECONDS);
  }

  private static boolean isPermanentlyKilled(IScheduledTask task) {
    boolean wasKilling =
        Iterables.any(
            task.getTaskEvents(),
            Predicates.compose(Predicates.equalTo(KILLING), Tasks.TASK_EVENT_TO_STATUS));
    return task.getStatus() != KILLING && wasKilling;
  }

  private static boolean isKillable(ScheduleStatus status) {
    return Tasks.isActive(status) && status != KILLING;
  }

  private static boolean isTaskPresent(Optional<IScheduledTask> task) {
    return task.isPresent() && !isPermanentlyKilled(task.get());
  }

  @Override
  public synchronized StateEvaluator.Result evaluate(Optional<IScheduledTask> actualState) {
    boolean desiredPresent = desiredState.isPresent();
    boolean actualPresent = isTaskPresent(actualState);

    if (desiredPresent && actualPresent) {
      // The update is changing the task configuration.
      return handleActualAndDesiredPresent(actualState.get());
    } else if (desiredPresent) {
      // The update is introducing a new instance.
      return REPLACE_TASK_AND_EVALUATE_ON_STATE_CHANGE;
    } else if (actualPresent) {
      // The update is removing an instance.
      return isKillable(actualState.get().getStatus())
          ? KILL_TASK_AND_EVALUATE_ON_STATE_CHANGE
          : EVALUATE_ON_STATE_CHANGE;
    } else {
      // No-op update.
      return SUCCEEDED;
    }
  }

  private boolean addFailureAndCheckIfFailed() {
    LOG.info("Observed updated task failure.");
    observedFailures++;
    return observedFailures > toleratedFailures;
  }

  private static ITaskConfig normalizeJobOwner(ITaskConfig taskConfig) {
    return ITaskConfig.build(
        taskConfig.newBuilder().setOwner(new Identity()));
  }

  private static boolean compareTaskConfigIgnoreOwner(ITaskConfig first, ITaskConfig second) {
    requireNonNull(first);
    requireNonNull(second);

    return normalizeJobOwner(first).equals(normalizeJobOwner(second));
  }

  private StateEvaluator.Result handleActualAndDesiredPresent(IScheduledTask actualState) {
    Preconditions.checkState(desiredState.isPresent());
    Preconditions.checkArgument(!actualState.getTaskEvents().isEmpty());

    ScheduleStatus status = actualState.getStatus();
    if (compareTaskConfigIgnoreOwner(
        desiredState.get(), actualState.getAssignedTask().getTask())) {
      // The desired task is in the system.
      if (status == RUNNING) {
        // The desired task is running.
        if (appearsStable(actualState)) {
          // Stably running, our work here is done.
          return SUCCEEDED;
        } else {
          // Not running long enough to consider stable, check again later.
          return EVALUATE_AFTER_MIN_RUNNING_MS;
        }
      } else if (Tasks.isTerminated(status)) {
        // The desired task has terminated, this is a failure.
        LOG.info("Task is in terminal state " + status);
        return addFailureAndCheckIfFailed() ? FAILED_TERMINATED : EVALUATE_ON_STATE_CHANGE;
      } else if (appearsStuck(actualState)) {
        LOG.info("Task appears stuck.");
        // The task is not running, but not terminated, and appears to have been in this state
        // long enough that we should intervene.
        return addFailureAndCheckIfFailed()
            ? FAILED_STUCK
            : KILL_TASK_AND_EVALUATE_ON_STATE_CHANGE;
      } else {
        // The task is in a transient state on the way into or out of running, check back later.
        return EVALUATE_AFTER_MIN_RUNNING_MS;
      }
    } else {
      // This is not the configuration that we would like to run.
      if (isKillable(status)) {
        // Task is active, kill it.
        return StateEvaluator.Result.KILL_TASK_AND_EVALUATE_ON_STATE_CHANGE;
      } else if (Tasks.isTerminated(status) && isPermanentlyKilled(actualState)) {
        // The old task has exited, it is now safe to add the new one.
        return StateEvaluator.Result.REPLACE_TASK_AND_EVALUATE_ON_STATE_CHANGE;
      }
    }

    return EVALUATE_ON_STATE_CHANGE;
  }
}
