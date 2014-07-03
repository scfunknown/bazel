// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.view.test;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.AnalysisEnvironment;
import com.google.devtools.build.lib.view.ConfiguredTarget;
import com.google.devtools.build.lib.view.FileProvider;
import com.google.devtools.build.lib.view.FilesToRunProvider;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.view.RuleContext;
import com.google.devtools.build.lib.view.RunfilesSupport;
import com.google.devtools.build.lib.view.TransitiveInfoCollection;
import com.google.devtools.build.lib.view.Util;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.test.TestProvider.TestParams;
import com.google.devtools.common.options.EnumConverter;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Helper class to create test actions.
 */
public final class TestHelper {
  /**
   * The alarm script is added to $PATH, for use by tests, in particular, shell-scripts.
   */
  @VisibleForTesting
  static final String ALARM = "alarm";


  private final RuleContext ruleContext;
  private RunfilesSupport runfilesSupport;
  private Artifact executable;
  private ExecutionRequirementProvider executionRequirements;
  private InstrumentedFilesProvider instrumentedFiles;
  private ImmutableList<Artifact> filesToRun;
  private int explicitShardCount;

  public TestHelper(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
  }

  /**
   * Creates the test actions and artifacts using the previously set parameters.
   *
   * @return ordered list of test status artifacts
   */
  public TestParams build() {
    Preconditions.checkState(runfilesSupport != null);
    boolean local = TargetUtils.isTestRuleAndRunsLocally(ruleContext.getRule());
    TestShardingStrategy strategy = ruleContext.getConfiguration().testShardingStrategy();
    int shards = strategy.getNumberOfShards(
        local, explicitShardCount, isTestShardingCompliant(),
        TestSize.getTestSize(ruleContext.getRule()));
    Preconditions.checkState(shards >= 0);
    ImmutableList<Artifact> testStatusArtifacts = createTestAction(
        Util.getWorkspaceRelativePath(ruleContext.getLabel()), shards);
    int runs = ruleContext.getConfiguration().getRunsPerTestForLabel(
        ruleContext.getLabel());
    return new TestParams(runs, shards, TestTimeout.getTestTimeout(ruleContext.getRule()),
        ruleContext.getRule().getRuleClass(), testStatusArtifacts);
  }

  private boolean isTestShardingCompliant() {
    // See if it has a data dependency on the special target
    // //tools:test_sharding_compliant. Test runners add this dependency
    // to show they speak the sharding protocol.
    // There are certain cases where this heuristic may fail, giving
    // a "false positive" (where we shard the test even though the
    // it isn't supported). We may want to refine this logic, but
    // heuristically sharding is currently experimental. Also, we do detect
    // false-positive cases and return an error.
    return runfilesSupport.getRunfilesSymlinkNames().contains(
        new PathFragment("tools/test_sharding_compliant"));
  }

  /**
   * Set the runfiles and executable to be run as a test.
   */
  public TestHelper setFilesToRunProvider(FilesToRunProvider provider) {
    Preconditions.checkNotNull(provider.getRunfilesSupport());
    Preconditions.checkNotNull(provider.getExecutable());
    this.runfilesSupport = provider.getRunfilesSupport();
    this.executable = provider.getExecutable();
    return this;
  }

  public TestHelper setFilesToRun(ImmutableList<Artifact> filesToRun) {
    Preconditions.checkNotNull(filesToRun);
    this.filesToRun = filesToRun;
    return this;
  }

  public TestHelper setInstrumentedFiles(@Nullable InstrumentedFilesProvider instrumentedFiles) {
    this.instrumentedFiles = instrumentedFiles;
    return this;
  }

  public TestHelper setExecutionRequirements(
      @Nullable ExecutionRequirementProvider executionRequirements) {
    this.executionRequirements = executionRequirements;
    return this;
  }

  /**
   * Set the explicit shard count. Note that this may be overridden by the sharding strategy.
   */
  public TestHelper setShardCount(int explicitShardCount) {
    this.explicitShardCount = explicitShardCount;
    return this;
  }

  /**
   * Converts to {@link TestHelper.TestShardingStrategy}.
   */
  public static class ShardingStrategyConverter extends EnumConverter<TestShardingStrategy> {
    public ShardingStrategyConverter() {
      super(TestShardingStrategy.class, "test sharding strategy");
    }
  }

  public static enum TestShardingStrategy {
    EXPLICIT {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        return Math.max(shardCountFromAttr, 0);
      }
    },

    EXPERIMENTAL_HEURISTIC {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        if (shardCountFromAttr >= 0) {
          return shardCountFromAttr;
        }
        if (isLocal || !testShardingCompliant) {
          return 0;
        }
        return testSize.getDefaultShards();
      }
    },

    DISABLED {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        return 0;
      }
    };

    public abstract int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
        boolean testShardingCompliant, TestSize testSize);
  }

  /**
   * Creates a test action and artifacts for the given rule. The test action will
   * use the specified executable and runfiles.
   *
   * @param targetName the google3 relative path of the target to run
   * @return ordered list of test status artifacts
   */
  private ImmutableList<Artifact> createTestAction(PathFragment targetName, int shards) {
    BuildConfiguration config = ruleContext.getConfiguration();
    AnalysisEnvironment env = ruleContext.getAnalysisEnvironment();
    Root root = config.getTestLogsDirectory();

    NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    inputsBuilder.addTransitive(
        NestedSetBuilder.create(Order.STABLE_ORDER, runfilesSupport.getRunfilesMiddleman()));
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("$test_tools", Mode.HOST)) {
      inputsBuilder.addTransitive(dep.getProvider(FileProvider.class).getFilesToBuild());
    }
    inputsBuilder.add(env.getEmbeddedToolArtifact(ALARM));
    TestTargetProperties testProperties = new TestTargetProperties(
        ruleContext, executionRequirements);

    // If the test rule does not provide InstrumentedFilesProvider, there's not much that we can do.
    final boolean collectCodeCoverage = config.isCodeCoverageEnabled()
        && instrumentedFiles != null;

    TestTargetExecutionSettings executionSettings;
    if (collectCodeCoverage) {
      // Add instrumented file manifest artifact to the list of inputs. This file will contain
      // exec paths of all source files that should be included into the code coverage output.
      Collection<Artifact> metadataFiles =
          ImmutableList.copyOf(instrumentedFiles.getInstrumentationMetadataFiles());
      inputsBuilder.addTransitive(NestedSetBuilder.wrap(Order.STABLE_ORDER, metadataFiles));
      for (TransitiveInfoCollection dep :
          ruleContext.getPrerequisites(":coverage_support", Mode.HOST)) {
        inputsBuilder.addTransitive(dep.getProvider(FileProvider.class).getFilesToBuild());
      }
      Artifact instrumentedFileManifest =
          InstrumentedFileManifestAction.getInstrumentedFileManifest(ruleContext,
              filesToRun,
              ImmutableList.copyOf(instrumentedFiles.getInstrumentedFiles()),
              metadataFiles);
      executionSettings = new TestTargetExecutionSettings(ruleContext, runfilesSupport,
          executable, instrumentedFileManifest, shards);
      inputsBuilder.add(instrumentedFileManifest);
    } else {
      executionSettings = new TestTargetExecutionSettings(ruleContext, runfilesSupport,
          executable, null, shards);
    }

    if (config.getRunUnder() != null) {
      Artifact runUnderExecutable = executionSettings.getRunUnderExecutable();
      if (runUnderExecutable != null) {
        inputsBuilder.add(runUnderExecutable);
      }
    }

    int runsPerTest = config.getRunsPerTestForLabel(ruleContext.getLabel());

    Iterable<Artifact> inputs = inputsBuilder.build();
    int shardRuns = (shards > 0 ? shards : 1);
    List<Artifact> results = Lists.newArrayListWithCapacity(runsPerTest * shardRuns);
    for (int run = 0; run < runsPerTest; run++) {
      // Use a 1-based index for user friendliness.
      String runSuffix =
          runsPerTest > 1 ? String.format("_run_%d_of_%d", run + 1, runsPerTest) : "";
      for (int shard = 0; shard < shardRuns; shard++) {
        String suffix = (shardRuns > 1 ? String.format("_shard_%d_of_%d", shard + 1, shards) : "")
            + runSuffix;
        Artifact testLog = env.getDerivedArtifact(
            targetName.getChild("test" + suffix + ".log"), root);
        Artifact testStatus = env.getDerivedArtifact(
            targetName.getChild("test" + suffix + ".status"), root);

        PathFragment coverageData = collectCodeCoverage
            ? root.getExecPath().getRelative(
                targetName.getChild("coverage" + suffix + ".dat"))
            : null;

        PathFragment microCoverageData = collectCodeCoverage && config.isMicroCoverageEnabled()
            ? root.getExecPath().getRelative(
                targetName.getChild("coverage" + suffix + ".micro.dat"))
            : null;

        env.registerAction(new TestRunnerAction(
            ruleContext.getActionOwner(), inputs,
            testLog, testStatus, coverageData, microCoverageData,
            testProperties, executionSettings,
            shard, run, config));
        results.add(testStatus);
      }
    }
    return ImmutableList.copyOf(results);
  }

  /**
   * Returns the collection of configured targets corresponding to any of the provided targets.
   */
  public static Iterable<? extends ConfiguredTarget> filterTestsByTargets(
      Collection<? extends ConfiguredTarget> targets, final Set<? extends Target> allowedTargets) {
    return Iterables.filter(targets,
        new Predicate<ConfiguredTarget>() {
          @Override
          public boolean apply(ConfiguredTarget rule) {
            return allowedTargets.contains(rule.getTarget());
          }
        });
  }

  /**
   * Returns the test status artifacts for a specified configured target
   *
   * @param target the configured target. Should belong to a test rule.
   * @return the test status artifacts
   */
  public static ImmutableList<Artifact> getTestStatusArtifacts(TransitiveInfoCollection target) {
    return target.getProvider(TestProvider.class).getTestParams().getTestStatusArtifacts();
  }
}
