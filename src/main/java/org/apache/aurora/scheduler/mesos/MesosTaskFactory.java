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
package org.apache.aurora.scheduler.mesos;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Data;

import org.apache.aurora.Protobufs;
import org.apache.aurora.codec.ThriftBinaryCodec;
import org.apache.aurora.scheduler.base.CommandUtil;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.SchedulerException;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.configuration.Resources;

import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.storage.entities.IDockerContainer;
import org.apache.aurora.scheduler.storage.entities.IJobKey;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.ContainerInfo;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.Volume;

import static java.util.Objects.requireNonNull;

/**
 * A factory to create mesos task objects.
 */
public interface MesosTaskFactory {

  /**
   * Creates a mesos task object.
   *
   * @param task Assigned task to translate into a task object.
   * @param slaveId Id of the slave the task is being assigned to.
   * @return A new task.
   * @throws SchedulerException If the task could not be encoded.
   */
  TaskInfo createFrom(IAssignedTask task, SlaveID slaveId) throws SchedulerException;

  class ExecutorSettings {

    private final String executorPath;
    private final List<String> executorResources;
    private final String thermosObserverRoot;
    private final Optional<String> executorFlags;
    private final Resources executorOverhead;

    public ExecutorSettings(
        String executorPath,
        List<String> executorResources,
        String thermosObserverRoot,
        Optional<String> executorFlags,
        Resources executorOverhead) {

      this.executorPath = requireNonNull(executorPath);
      this.executorResources = requireNonNull(executorResources);
      this.thermosObserverRoot = requireNonNull(thermosObserverRoot);
      this.executorFlags = requireNonNull(executorFlags);
      this.executorOverhead = requireNonNull(executorOverhead);
    }

    String getExecutorPath() {
      return executorPath;
    }

    List<String> getExecutorResources() {
      return executorResources;
    }

    String getThermosObserverRoot() {
      return thermosObserverRoot;
    }

    Optional<String> getExecutorFlags() {
      return executorFlags;
    }

    Resources getExecutorOverhead() {
      return executorOverhead;
    }
  }

  // TODO(wfarner): Move this class to its own file to reduce visibility to package private.
  class MesosTaskFactoryImpl implements MesosTaskFactory {
    private static final Logger LOG = Logger.getLogger(MesosTaskFactoryImpl.class.getName());
    private static final String EXECUTOR_PREFIX = "thermos-";

    /**
     * Minimum resources required to run Thermos. In the wild Thermos needs about 0.01 CPU and
     * about 170MB (peak usage) of RAM. The RAM requirement has been rounded up to a power of 2.
     */
    @VisibleForTesting
    static final Resources MIN_THERMOS_RESOURCES = new Resources(
        0.01,
        Amount.of(256L, Data.MB),
        Amount.of(1L, Data.MB),
        0);

    /**
     * Minimum resources to allocate for a task. Mesos rejects tasks that have no CPU, no RAM, or
     * no Disk.
     */
    @VisibleForTesting
    static final Resources MIN_TASK_RESOURCES = new Resources(
        0.01,
        Amount.of(1L, Data.MB),
        Amount.of(1L, Data.MB),
        0);

    /**
     * Name to associate with task executors.
     */
    @VisibleForTesting
    static final String EXECUTOR_NAME = "aurora.task";

    /**
     * This script sets up a symlink inside the docker container to the same path
     * as the sandbox outside the container.  This allows the executor /observer
     * to see the same path inside and outside.  It also changes the working directory
     * to the sandbox (which the executor expects).
     */
    private static final String DOCKER_COMMAND_PREFIX =
        "mkdir -p `dirname $MESOS_DIRECTORY` && "
      + "ln -s $MESOS_SANDBOX $MESOS_DIRECTORY && "
      + "cd $MESOS_DIRECTORY && ";

    private static final String DOCKER_COMMAND_SUFFIX = " --nosetuid";

    private final ExecutorSettings executorSettings;

    @Inject
    MesosTaskFactoryImpl(ExecutorSettings executorSettings) {
      this.executorSettings = requireNonNull(executorSettings);
    }

    @VisibleForTesting
    static ExecutorID getExecutorId(String taskId) {
      return ExecutorID.newBuilder().setValue(EXECUTOR_PREFIX + taskId).build();
    }

    private static String getJobSourceName(IJobKey jobkey) {
      return String.format("%s.%s.%s", jobkey.getRole(), jobkey.getEnvironment(), jobkey.getName());
    }

    private static String getJobSourceName(ITaskConfig task) {
      return getJobSourceName(task.getJob());
    }

    @VisibleForTesting
    static String getInstanceSourceName(ITaskConfig task, int instanceId) {
      return String.format("%s.%s", getJobSourceName(task), instanceId);
    }

    /**
     * Generates a Resource where each resource component is a max out of the two components.
     *
     * @param a A resource to compare.
     * @param b A resource to compare.
     *
     * @return Returns a Resources instance where each component is a max of the two components.
     */
    @VisibleForTesting
    static Resources maxElements(Resources a, Resources b) {
      double maxCPU = Math.max(a.getNumCpus(), b.getNumCpus());
      Amount<Long, Data> maxRAM = Amount.of(
          Math.max(a.getRam().as(Data.MB), b.getRam().as(Data.MB)),
          Data.MB);
      Amount<Long, Data> maxDisk = Amount.of(
          Math.max(a.getDisk().as(Data.MB), b.getDisk().as(Data.MB)),
          Data.MB);
      int maxPorts = Math.max(a.getNumPorts(), b.getNumPorts());

      return new Resources(maxCPU, maxRAM, maxDisk, maxPorts);
    }

    @Override
    public TaskInfo createFrom(IAssignedTask task, SlaveID slaveId) throws SchedulerException {
      requireNonNull(task);
      requireNonNull(slaveId);

      byte[] taskInBytes;
      try {
        taskInBytes = ThriftBinaryCodec.encode(task.newBuilder());
      } catch (ThriftBinaryCodec.CodingException e) {
        LOG.log(Level.SEVERE, "Unable to serialize task.", e);
        throw new SchedulerException("Internal error.", e);
      }

      // The objective of the below code is to allocate a task and executor that is in a container
      // of task + executor overhead size. Mesos stipulates that we cannot allocate 0 sized tasks or
      // executors and we should always ensure the ExecutorInfo has enough resources to launch or
      // run an executor. Therefore the total desired container size (task + executor overhead) is
      // partitioned to a small portion that is always allocated to the executor and the rest to the
      // task. If the remaining resources are not enough for the task a small epsilon is allocated
      // to the task.

      ITaskConfig config = task.getTask();
      Resources taskResources = Resources.from(config);
      Resources containerResources =
          Resources.sum(taskResources, executorSettings.getExecutorOverhead());

      taskResources = Resources.subtract(containerResources, MIN_THERMOS_RESOURCES);
      // It is possible that the final task resources will be negative.
      // This ensures the task resources are positive.
      Resources finalTaskResources = maxElements(taskResources, MIN_TASK_RESOURCES);

      // TODO(wfarner): Re-evaluate if/why we need to continue handling unset assignedPorts field.
      List<Resource> resources = finalTaskResources
          .toResourceList(task.isSetAssignedPorts()
              ? ImmutableSet.copyOf(task.getAssignedPorts().values())
              : ImmutableSet.<Integer>of());

      LOG.fine("Setting task resources to "
          + Iterables.transform(resources, Protobufs.SHORT_TOSTRING));

      TaskInfo.Builder taskBuilder =
          TaskInfo.newBuilder()
              .setName(JobKeys.canonicalString(Tasks.ASSIGNED_TO_JOB_KEY.apply(task)))
              .setTaskId(TaskID.newBuilder().setValue(task.getTaskId()))
              .setSlaveId(slaveId)
              .addAllResources(resources)
              .setData(ByteString.copyFrom(taskInBytes));

      if (config.getContainer().isSetMesos()) {
        configureTaskForNoContainer(task, config, taskBuilder);
      } else if (config.getContainer().isSetDocker()) {
        configureTaskForDockerContainer(task, config, taskBuilder);
      } else {
        throw new SchedulerException("Task had no supported container set.");
      }

      return taskBuilder.build();
    }

    private void configureTaskForNoContainer(
        IAssignedTask task,
        ITaskConfig config,
        TaskInfo.Builder taskBuilder) {

      CommandInfo commandInfo = CommandUtil.create(
          executorSettings.getExecutorPath(),
          executorSettings.getExecutorResources(),
          Optional.<String>absent(),
          Optional.<String>absent(),
          executorSettings.getExecutorFlags()).build();

      ExecutorInfo.Builder executorBuilder = configureTaskForExecutor(task, config, commandInfo);
      taskBuilder.setExecutor(executorBuilder.build());
    }

    private void configureTaskForDockerContainer(
        IAssignedTask task,
        ITaskConfig taskConfig,
        TaskInfo.Builder taskBuilder) {

      IDockerContainer config = taskConfig.getContainer().getDocker();
      ContainerInfo.DockerInfo.Builder dockerBuilder = ContainerInfo.DockerInfo.newBuilder()
          .setImage(config.getImage());

      ContainerInfo.Builder containerBuilder = ContainerInfo.newBuilder()
          .setType(ContainerInfo.Type.DOCKER)
          .setDocker(dockerBuilder.build());

      configureContainerVolumes(containerBuilder);

      // TODO(SteveNiemitz): Allow users to specify an executor per container type.
      CommandInfo.Builder commandInfoBuilder = CommandUtil.create(
          executorSettings.getExecutorPath(),
          executorSettings.getExecutorResources(),
          Optional.of(DOCKER_COMMAND_PREFIX),
          Optional.of(DOCKER_COMMAND_SUFFIX),
          executorSettings.getExecutorFlags());

      ExecutorInfo.Builder execBuilder =
          configureTaskForExecutor(task, taskConfig, commandInfoBuilder.build())
              .setContainer(containerBuilder.build());

      taskBuilder.setExecutor(execBuilder.build());
    }

    private ExecutorInfo.Builder configureTaskForExecutor(
        IAssignedTask task,
        ITaskConfig config,
        CommandInfo commandInfo) {

      return ExecutorInfo.newBuilder()
          .setCommand(commandInfo)
          .setExecutorId(getExecutorId(task.getTaskId()))
          .setName(EXECUTOR_NAME)
          .setSource(getInstanceSourceName(config, task.getInstanceId()))
          .addAllResources(MIN_THERMOS_RESOURCES.toResourceList());
    }

    private void configureContainerVolumes(ContainerInfo.Builder containerBuilder) {
      containerBuilder.addVolumes(
          Volume.newBuilder()
              .setContainerPath(executorSettings.getThermosObserverRoot())
              .setHostPath(executorSettings.getThermosObserverRoot())
              .setMode(Volume.Mode.RW)
              .build());
    }
  }
}
