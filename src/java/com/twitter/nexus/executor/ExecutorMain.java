package com.twitter.nexus.executor;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.twitter.common.args.Option;
import com.twitter.common.base.Command;
import com.twitter.common.inject.process.InjectableMain;
import com.twitter.common.process.GuicedProcessOptions;
import com.twitter.nexus.util.HdfsUtil;
import nexus.NexusExecutorDriver;
import org.apache.hadoop.fs.FileSystem;

import java.io.File;
import java.util.logging.Logger;

/**
 * ExecutorMain
 *
 * @author Florian Leibert
 */
public class ExecutorMain extends InjectableMain<ExecutorMain.TwitterExecutorOptions, Exception> {
  private static Logger LOG = Logger.getLogger(ExecutorMain.class.getName());

  public static class TwitterExecutorOptions extends GuicedProcessOptions {
    @Option(name = "hdfs_config", required = true, usage = "Hadoop configuration path")
    public String hdfsConfig;

    @Option(name = "kill_tree_path", usage = "HDFS path to kill tree shell script")
    public String killTreeHdfsPath;

    @Option(name = "task_root_dir", required = true, usage = "Nexus task working directory root.")
    public File taskRootDir;

    @Option(name = "managed_port_range",
        usage = "Port range that the executor should manage, format: min-max")
    public String managedPortRange = "50000-60000";
  }

  @Inject
  private ExecutorHub executorHub;

  @Inject
  private ExecutorCore executorCore;

  @Inject
  private FileSystem fileSystem;

  protected ExecutorMain() {
    super(TwitterExecutorOptions.class);
  }

  @Override
  public void execute() throws Exception {
    addShutdownAction(new Command() {
      @Override public void execute() {
        System.out.println("Shutting down the executor.");
        executorCore.shutdownCore(null);
      }
    });

    // Fetch the killtree script.
    LOG.info("Fetching killtree script.");
    HdfsUtil.downloadFileFromHdfs(fileSystem, getOptions().killTreeHdfsPath,
        getOptions().taskRootDir.getAbsolutePath());

    new NexusExecutorDriver(executorHub).run();
  }

  @Override
  protected Iterable<Class<? extends Module>> getModuleClasses() {
    return ImmutableList.<Class<? extends Module>>of(ExecutorModule.class);
  }

  public static void main(String[] args) throws Exception {
    new ExecutorMain().run(args);
  }
}
