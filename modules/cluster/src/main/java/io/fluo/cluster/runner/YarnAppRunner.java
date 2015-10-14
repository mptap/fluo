/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.cluster.runner;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.google.common.base.Preconditions;
import io.fluo.accumulo.util.ZookeeperPath;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.api.exceptions.FluoException;
import io.fluo.cluster.main.FluoOracleMain;
import io.fluo.cluster.main.FluoWorkerMain;
import io.fluo.cluster.yarn.FluoTwillApp;
import io.fluo.cluster.yarn.TwillUtil;
import io.fluo.core.client.FluoAdminImpl;
import io.fluo.core.util.CuratorUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.twill.api.ResourceReport;
import org.apache.twill.api.TwillController;
import org.apache.twill.api.TwillPreparer;
import org.apache.twill.api.TwillRunResources;
import org.apache.twill.api.TwillRunnerService;
import org.apache.twill.internal.RunIds;
import org.apache.twill.yarn.YarnTwillRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yarn Implementation of ClusterAppRunner
 */
public class YarnAppRunner extends ClusterAppRunner implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(YarnAppRunner.class);
  private Map<String, TwillRunnerService> twillRunners = new HashMap<>();
  private Map<String, CuratorFramework> curators = new HashMap<>();
  private String hadoopPrefix;

  public YarnAppRunner(String hadoopPrefix) {
    super("fluo");
    this.hadoopPrefix = hadoopPrefix;
  }

  private synchronized CuratorFramework getAppCurator(FluoConfiguration config) {
    if (!curators.containsKey(config.getApplicationName())) {
      CuratorFramework curator = CuratorUtil.newAppCurator(config);
      curator.start();
      curators.put(config.getApplicationName(), curator);
    }
    return curators.get(config.getApplicationName());
  }

  private synchronized TwillRunnerService getTwillRunner(FluoConfiguration config) {
    if (!twillRunners.containsKey(config.getApplicationName())) {
      YarnConfiguration yarnConfig = new YarnConfiguration();
      yarnConfig.addResource(new Path(hadoopPrefix + "/etc/hadoop/core-site.xml"));
      yarnConfig.addResource(new Path(hadoopPrefix + "/etc/hadoop/yarn-site.xml"));

      TwillRunnerService twillRunner =
          new YarnTwillRunnerService(yarnConfig, config.getAppZookeepers() + ZookeeperPath.TWILL);
      twillRunner.start();

      twillRunners.put(config.getApplicationName(), twillRunner);

      // sleep to give twill time to retrieve state from zookeeper
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
    return twillRunners.get(config.getApplicationName());
  }

  private void checkIfInitialized(FluoConfiguration config) {

    try (FluoAdminImpl admin = new FluoAdminImpl(config)) {
      if (!admin.zookeeperInitialized()) {
        throw new FluoException("A Fluo '" + config.getApplicationName() + "' application has not "
            + "been initialized yet in Zookeeper at " + config.getAppZookeepers());
      }
    }
  }

  public void list(FluoConfiguration config) {
    try (CuratorFramework curator = CuratorUtil.newFluoCurator(config)) {
      curator.start();

      try {
        if (curator.checkExists().forPath("/") == null) {
          System.out.println("Fluo instance (" + config.getInstanceZookeepers() + ") has not been "
              + "created yet in Zookeeper.  It will be created when the first Fluo application is "
              + "initialized for this instance.");
          return;
        }
        List<String> children = curator.getChildren().forPath("/");
        if (children.isEmpty()) {
          System.out.println("Fluo instance (" + config.getInstanceZookeepers() + ") does not "
              + "contain any Fluo applications.");
          return;
        }
        Collections.sort(children);

        System.out.println("Fluo instance (" + config.getInstanceZookeepers() + ") contains "
            + children.size() + " application(s)\n");
        System.out.println("Application     Status      YarnAppName          YarnAppId");
        System.out.println("-----------     ------      -----------          ---------");
        for (String path : children) {
          FluoConfiguration appConfig = new FluoConfiguration(config);
          appConfig.setApplicationName(path);
          String state = "INITIALIZED";
          String yarnId = "not started yet";
          String yarnAppName = getYarnApplicationName(path);
          if (twillIdExists(appConfig)) {
            String twillId = getTwillId(appConfig);
            yarnId = getAppId(appConfig);
            TwillController controller =
                getTwillRunner(appConfig).lookup(
                    getYarnApplicationName(appConfig.getApplicationName()),
                    RunIds.fromString(twillId));
            if (controller == null) {
              state = "STOPPED";
            } else {
              state = "RUNNING";
            }
          }
          System.out.format("%-15s %-11s %-20s %s\n", path, state, yarnAppName, yarnId);
        }
      } catch (Exception e) {
        throw new FluoException(e);
      }
    }
  }

  public void start(FluoConfiguration config, String appConfDir, String appLibPath,
      String fluoLibPath) {

    checkIfInitialized(config);

    if (twillIdExists(config)) {
      String runId = getTwillId(config);

      TwillController controller =
          getTwillRunner(config).lookup(getYarnApplicationName(config.getApplicationName()),
              RunIds.fromString(runId));
      if ((controller != null) && isReady(controller)) {
        throw new FluoException("A YARN application " + getAppInfo(config)
            + " is already running for the Fluo '" + config.getApplicationName()
            + "' application!  Please stop it using 'fluo stop " + config.getApplicationName()
            + "' before starting a new one.");
      }
    }

    if (!config.hasRequiredOracleProps() || !config.hasRequiredWorkerProps()) {
      throw new FluoException("Failed to start Fluo '" + config.getApplicationName()
          + "' application because fluo.properties is missing required properties.");
    }

    try {
      config.validate();
    } catch (IllegalArgumentException e) {
      throw new FluoException("Invalid fluo.properties due to " + e.getMessage());
    } catch (Exception e) {
      throw new FluoException("Invalid fluo.properties due to " + e.getMessage(), e);
    }

    TwillPreparer preparer = getTwillRunner(config).prepare(new FluoTwillApp(config, appConfDir));

    // Add jars from fluo lib/ directory that are not being loaded by Twill.
    try {
      File libDir = new File(fluoLibPath);
      File[] libFiles = libDir.listFiles();
      if (libFiles != null) {
        for (File f : libFiles) {
          if (f.isFile()) {
            String jarPath = "file:" + f.getCanonicalPath();
            log.trace("Adding library jar (" + f.getName() + ") to Fluo application.");
            preparer.withResources(new URI(jarPath));
          }
        }
      }

      // Add jars found in application's lib dir
      File appLibDir = new File(appLibPath);
      File[] appFiles = appLibDir.listFiles();
      if (appFiles != null) {
        for (File f : appFiles) {
          String jarPath = "file:" + f.getCanonicalPath();
          log.debug("Adding application jar (" + f.getName() + ") to Fluo application.");
          preparer.withResources(new URI(jarPath));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    Preconditions.checkNotNull(preparer, "Failed to prepare twill application");

    log.info("Starting Fluo '{}' application in YARN...", config.getApplicationName());
    TwillController controller = preparer.start();

    try {
      // set twill run id zookeeper
      String twillId = controller.getRunId().toString();
      CuratorUtil.putData(getAppCurator(config), ZookeeperPath.YARN_TWILL_ID,
          twillId.getBytes(StandardCharsets.UTF_8), CuratorUtil.NodeExistsPolicy.OVERWRITE);

      // set app id in zookeeper
      String appId = getResourceReport(controller, -1).getApplicationId();
      Preconditions.checkNotNull(appId, "Failed to retrieve YARN app ID from Twill");
      CuratorUtil.putData(getAppCurator(config), ZookeeperPath.YARN_APP_ID,
          appId.getBytes(StandardCharsets.UTF_8), CuratorUtil.NodeExistsPolicy.OVERWRITE);

      log.info("The Fluo '{}' application is running in YARN {}", config.getApplicationName(),
          getAppInfo(config));

      log.info("Waiting for all desired containers to start...");
      int checks = 0;
      while (!allContainersRunning(controller, config)) {
        Thread.sleep(500);
        checks++;
        if (checks == 30) {
          log.warn("Still waiting... YARN may not have enough resources available for this "
              + "application.  Use ctrl-c to stop waiting and check status using "
              + "'fluo info <app>'.");
        }
      }
      log.info("All desired containers are running in YARN " + getAppInfo(config));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void stop(FluoConfiguration config) throws InterruptedException, ExecutionException {
    checkIfInitialized(config);
    String twillId = verifyTwillId(config);

    TwillController controller =
        getTwillRunner(config).lookup(getYarnApplicationName(config.getApplicationName()),
            RunIds.fromString(twillId));
    if ((controller != null) && isReady(controller)) {
      System.out.print("Stopping Fluo '" + config.getApplicationName() + "' application "
          + getAppInfo(config) + "...");
      controller.terminate().get();
      System.out.println("DONE");
    } else {
      System.out.println("Fluo '" + config.getApplicationName() + "' application "
          + getAppInfo(config) + " is already stopped.");
    }
  }

  public void kill(FluoConfiguration config) throws Exception {
    checkIfInitialized(config);

    String twillId = verifyTwillId(config);

    TwillController controller =
        getTwillRunner(config).lookup(getYarnApplicationName(config.getApplicationName()),
            RunIds.fromString(twillId));
    if (controller != null) {
      System.out.print("Killing Fluo '" + config.getApplicationName() + "' application "
          + getAppInfo(config) + "...");
      controller.kill();
      System.out.println("DONE");
    } else {
      System.out.println("Fluo '" + config.getApplicationName() + "' application "
          + getAppInfo(config) + " is already stopped.");
    }
  }

  /**
   * Attempts to retrieves ResourceReport until maxWaitMs time is reached.
   * Set maxWaitMs to -1 to retry forever.
   */
  private ResourceReport getResourceReport(TwillController controller, int maxWaitMs) {
    ResourceReport report = controller.getResourceReport();
    int elapsed = 0;
    while (report == null) {
      report = controller.getResourceReport();
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      elapsed += 500;
      if ((maxWaitMs != -1) && (elapsed > maxWaitMs)) {
        String msg = String.format("Exceeded max wait time to retrieve ResourceReport from Twill."
                                   + " Elapsed time = %s ms", elapsed);
        log.error(msg);
        throw new IllegalStateException(msg);
      }
      if ((elapsed % 10000) == 0) {
        log.info("Waiting for ResourceReport from Twill. Elapsed time = {} ms", elapsed);
      }
    }
    return report;
  }

  private boolean isReady(TwillController controller) {
    try {
      if (getResourceReport(controller, 30000) != null) {
        return true;
      }
    } catch (Exception e) {
      log.error("Exception occurred while getting Twill resource report", e);
    }
    return false;
  }

  private boolean allContainersRunning(TwillController controller, FluoConfiguration config) {
    return TwillUtil.numRunning(controller, FluoOracleMain.ORACLE_NAME) == config
        .getOracleInstances()
        && TwillUtil.numRunning(controller, FluoWorkerMain.WORKER_NAME) == config
            .getWorkerInstances();
  }

  private String containerStatus(TwillController controller, FluoConfiguration config) {
    return "" + TwillUtil.numRunning(controller, FluoOracleMain.ORACLE_NAME) + " of "
        + config.getOracleInstances() + " Oracle containers and "
        + TwillUtil.numRunning(controller, FluoWorkerMain.WORKER_NAME) + " of "
        + config.getWorkerInstances() + " Worker containers";
  }

  public void status(FluoConfiguration config, boolean extraInfo) {
    checkIfInitialized(config);
    if (!twillIdExists(config)) {
      System.out.println("Fluo '" + config.getApplicationName()
          + "' application was initialized but has not been started.");
      return;
    }
    String twillId = getTwillId(config);
    TwillController controller =
        getTwillRunner(config).lookup(getYarnApplicationName(config.getApplicationName()),
            RunIds.fromString(twillId));
    if (controller == null) {
      System.out.print("Fluo '" + config.getApplicationName() + "' application "
          + getAppInfo(config) + " has stopped.");
    } else {
      System.out.println("A Fluo '" + config.getApplicationName() + "' application is running"
          + " in YARN " + getFullInfo(config));

      if (!allContainersRunning(controller, config)) {
        System.out.println("\nWARNING - The Fluo application is not running all desired "
            + "containers!  YARN may not have enough available resources.  Application is "
            + "currently running " + containerStatus(controller, config));
      }

      if (extraInfo) {
        ResourceReport report = getResourceReport(controller, 30000);
        Collection<TwillRunResources> resources;
        resources = report.getRunnableResources(FluoOracleMain.ORACLE_NAME);
        System.out.println("\nThe application has " + resources.size() + " of "
            + config.getOracleInstances() + " desired Oracle containers:\n");
        TwillUtil.printResources(resources);

        resources = report.getRunnableResources(FluoWorkerMain.WORKER_NAME);
        System.out.println("\nThe application has " + resources.size() + " of "
            + config.getWorkerInstances() + " desired Worker containers:\n");
        TwillUtil.printResources(resources);
      }
    }
  }

  private String verifyTwillId(FluoConfiguration config) {
    if (!twillIdExists(config)) {
      throw new FluoException("A YARN application is not referenced in Zookeeper for this "
          + " Fluo application.  Check if there is a Fluo application running in YARN using the "
          + "command 'yarn application -list`. If so, verify that your fluo.properties is "
          + "configured correctly.");
    }
    return getTwillId(config);
  }

  private String getAppInfo(FluoConfiguration config) {
    return "(yarn id = " + getAppId(config) + ")";
  }

  private String getFullInfo(FluoConfiguration config) {
    return "(yarn id = " + getAppId(config) + ", twill id = " + getTwillId(config) + ")";
  }

  private boolean twillIdExists(FluoConfiguration config) {
    try {
      return getAppCurator(config).checkExists().forPath(ZookeeperPath.YARN_TWILL_ID) != null;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String getTwillId(FluoConfiguration config) {
    try {
      return new String(getAppCurator(config).getData().forPath(ZookeeperPath.YARN_TWILL_ID),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String getAppId(FluoConfiguration config) {
    try {
      return new String(getAppCurator(config).getData().forPath(ZookeeperPath.YARN_APP_ID),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String getYarnApplicationName(String appName) {
    return String.format("fluo-app-%s", appName);
  }

  @Override
  public void close() throws Exception {
    for (TwillRunnerService twillRunner : twillRunners.values()) {
      twillRunner.stop();
    }
    for (CuratorFramework curator : curators.values()) {
      curator.close();
    }
  }
}
