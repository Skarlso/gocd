/*
 * Copyright Thoughtworks, Inc.
 *
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
package com.thoughtworks.go.server.service;

import com.thoughtworks.go.config.*;
import com.thoughtworks.go.config.elastic.ClusterProfile;
import com.thoughtworks.go.config.elastic.ElasticProfile;
import com.thoughtworks.go.config.exceptions.StageNotFoundException;
import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.helper.*;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.util.Clock;
import com.thoughtworks.go.util.GoConstants;
import com.thoughtworks.go.util.TestingClock;
import com.thoughtworks.go.util.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.thoughtworks.go.domain.JobResult.Passed;
import static com.thoughtworks.go.domain.JobResult.Unknown;
import static com.thoughtworks.go.domain.JobState.Completed;
import static com.thoughtworks.go.domain.JobState.Scheduled;
import static com.thoughtworks.go.helper.ModificationsMother.modifyOneFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceFactoryTest {
    private InstanceFactory instanceFactory;
    private Clock clock;

    @BeforeEach
    void setUp() {
        instanceFactory = new InstanceFactory();
        this.clock = new TestingClock();
    }

    @Test
    void shouldSetTheConfigVersionOnSchedulingAStage() {
        PipelineConfig pipelineConfig = PipelineConfigMother.createPipelineConfig("foo-pipeline", "foo-stage", "foo-job");
        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser");
        String md5 = "foo-md5";

        Stage actualStage = instanceFactory.createStageInstance(pipelineConfig, new CaseInsensitiveString("foo-stage"), schedulingContext, md5, clock);

        assertThat(actualStage.getConfigVersion()).isEqualTo(md5);
    }

    @Test
    void shouldThrowStageNotFoundExceptionWhenStageDoesNotExist() {
        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString("cruise"), new MaterialConfigs(), new StageConfig(new CaseInsensitiveString("first"), new JobConfigs()));
        try {
            instanceFactory.createStageInstance(pipelineConfig, new CaseInsensitiveString("doesNotExist"), new DefaultSchedulingContext(), "md5", clock);
            fail("Found the stage doesNotExist but, well, it doesn't");
        } catch (StageNotFoundException expected) {
            assertThat(expected.getMessage()).isEqualTo("Stage 'doesNotExist' not found in pipeline 'cruise'");
        }
    }

    @Test
    void shouldCreateAStageInstanceThroughInstanceFactory() {
        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString("cruise"), new MaterialConfigs(),
            new StageConfig(new CaseInsensitiveString("first"), new JobConfigs(new JobConfig("job1"), new JobConfig("job2"))));

        Stage actualStage = instanceFactory.createStageInstance(pipelineConfig, new CaseInsensitiveString("first"), new DefaultSchedulingContext(), "md5", clock);

        JobInstances jobInstances = new JobInstances();
        jobInstances.add(new JobInstance("job1", clock));
        jobInstances.add(new JobInstance("job2", clock));

        Stage expectedStage = new Stage("first", jobInstances, "Unknown", null, Approval.SUCCESS, clock);
        assertThat(actualStage).isEqualTo(expectedStage);
    }

    @Test
    void shouldCreatePipelineInstanceWithEnvironmentVariablesOverriddenAccordingToScope() {
        StageConfig stageConfig = StageConfigMother.custom("stage", "foo", "bar");
        JobConfig fooConfig = stageConfig.jobConfigByConfigName(new CaseInsensitiveString("foo"));
        fooConfig.addVariable("foo", "foo");
        JobConfig barConfig = stageConfig.jobConfigByConfigName(new CaseInsensitiveString("bar"));
        barConfig.addVariable("foo", "bar");
        MaterialConfigs materialConfigs = MaterialConfigsMother.defaultMaterialConfigs();
        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString("pipeline"), materialConfigs, stageConfig);
        DefaultSchedulingContext context = new DefaultSchedulingContext("anonymous");

        Pipeline instance = instanceFactory.createPipelineInstance(pipelineConfig, ModificationsMother.forceBuild(pipelineConfig), context, "some-md5", new TimeProvider());

        assertThat(instance.findStage("stage").findJob("foo").getPlan().getVariables()).isEqualTo(new EnvironmentVariables(List.of(new EnvironmentVariable("foo", "foo"))));
        assertThat(instance.findStage("stage").findJob("bar").getPlan().getVariables()).isEqualTo(new EnvironmentVariables(List.of(new EnvironmentVariable("foo", "bar"))));
    }

    @Test
    void shouldOverridePipelineEnvironmentVariablesFromBuildCauseForLabel() {
        StageConfig stageConfig = StageConfigMother.custom("stage", "foo", "bar");
        MaterialConfigs materialConfigs = MaterialConfigsMother.defaultMaterialConfigs();
        DefaultSchedulingContext context = new DefaultSchedulingContext("anonymous");

        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString("pipeline"), materialConfigs, stageConfig);
        pipelineConfig.addEnvironmentVariable("VAR", "value");
        pipelineConfig.setLabelTemplate("${ENV:VAR}");

        BuildCause buildCause = ModificationsMother.forceBuild(pipelineConfig);
        EnvironmentVariables overriddenVars = buildCause.getVariables();
        overriddenVars.add("VAR", "overriddenValue");
        buildCause.setVariables(overriddenVars);

        Pipeline instance = instanceFactory.createPipelineInstance(pipelineConfig, buildCause, context, "some-md5", new TimeProvider());
        instance.updateCounter(1);
        assertThat(instance.getLabel()).isEqualTo("overriddenValue");
    }

    @Test
    void shouldSchedulePipelineWithFirstStage() {
        StageConfig stageOneConfig = StageConfigMother.stageConfig("dev", BuildPlanMother.withBuildPlans("functional", "unit"));
        StageConfig stageTwoConfig = StageConfigMother.stageConfig("qa", BuildPlanMother.withBuildPlans("suiteOne", "suiteTwo"));

        MaterialConfigs materialConfigs = MaterialConfigsMother.defaultMaterialConfigs();
        PipelineConfig pipelineConfig = new PipelineConfig(new CaseInsensitiveString("mingle"), materialConfigs, stageOneConfig, stageTwoConfig);

        BuildCause buildCause = BuildCause.createManualForced(modifyOneFile(pipelineConfig), Username.ANONYMOUS);
        Pipeline pipeline = instanceFactory.createPipelineInstance(pipelineConfig, buildCause, new DefaultSchedulingContext("test"), "some-md5", new TimeProvider());

        assertThat(pipeline.getName()).isEqualTo("mingle");
        assertThat(pipeline.getStages().size()).isEqualTo(1);
        assertThat(pipeline.getStages().get(0).getName()).isEqualTo("dev");
        assertThat(pipeline.getStages().get(0).getJobInstances().get(0).getName()).isEqualTo("functional");
    }

    @Test
    void shouldSetAutoApprovalOnStageInstance() {
        StageConfig stageConfig = StageConfigMother.custom("test", Approval.automaticApproval());
        Stage instance = instanceFactory.createStageInstance(stageConfig, new DefaultSchedulingContext("anyone"), "md5", new TimeProvider());
        assertThat(instance.getApprovalType()).isEqualTo(GoConstants.APPROVAL_SUCCESS);
    }

    @Test
    void shouldSetManualApprovalOnStageInstance() {
        StageConfig stageConfig = StageConfigMother.custom("test", Approval.manualApproval());
        Stage instance = instanceFactory.createStageInstance(stageConfig, new DefaultSchedulingContext("anyone"), "md5", new TimeProvider());
        assertThat(instance.getApprovalType()).isEqualTo(GoConstants.APPROVAL_MANUAL);
    }

    @Test
    void shouldSetFetchMaterialsFlagOnStageInstance() {
        StageConfig stageConfig = StageConfigMother.custom("test", Approval.automaticApproval());
        stageConfig.setFetchMaterials(false);
        Stage instance = instanceFactory.createStageInstance(stageConfig, new DefaultSchedulingContext("anyone"), "md5", new TimeProvider());
        assertThat(instance.shouldFetchMaterials()).isFalse();
    }

    @Test
    void shouldClear_DatabaseIds_State_and_Result_ForJobObjectHierarchy() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        JobInstance rails = jobInstance(old, "rails", 7, 10);
        JobInstance java = jobInstance(old, "java", 12, 22);
        Stage stage = stage(9, rails, java);
        assertThat(stage.hasRerunJobs()).isFalse();

        Stage newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails"), new DefaultSchedulingContext("loser", new Agents()), StageConfigMother.custom("dev", "rails", "java"),
            new TimeProvider(), "md5");
        assertThat(stage.hasRerunJobs()).isFalse();

        assertThat(newStage.getId()).isEqualTo(-1L);
        assertThat(newStage.getJobInstances().size()).isEqualTo(2);
        assertThat(newStage.isLatestRun()).isTrue();

        JobInstance newRails = newStage.getJobInstances().getByName("rails");
        assertNewJob(old, newRails);

        JobInstance newJava = newStage.getJobInstances().getByName("java");
        assertCopiedJob(newJava, 12L);
    }

    @Test
    void should_MaintainRerunOfReferences_InCaseOfMultipleCopyForRerunOperations() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        JobInstance rails = jobInstance(old, "rails", 7, 10);
        JobInstance java = jobInstance(old, "java", 12, 22);
        Stage stage = stage(9, rails, java);
        stage.setCounter(2);

        Stage newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails"), new DefaultSchedulingContext("loser", new Agents()), StageConfigMother.custom("dev", "rails", "java"),
            new TimeProvider(), "md5");
        newStage.setCounter(3);
        assertThat(newStage.getId()).isEqualTo(-1L);
        assertThat(newStage.getJobInstances().size()).isEqualTo(2);
        assertThat(newStage.isLatestRun()).isTrue();
        assertThat(newStage.getRerunOfCounter()).isEqualTo(2);

        JobInstance newJava = newStage.getJobInstances().getByName("java");
        assertCopiedJob(newJava, 12L);

        //set id, to assert if original ends up pointing to copied job's id
        newJava.setId(18L);

        newStage = instanceFactory.createStageForRerunOfJobs(newStage, List.of("rails"), new DefaultSchedulingContext("loser", new Agents()), StageConfigMother.custom("dev", "rails", "java"),
            new TimeProvider(), "md5");
        newStage.setCounter(4);
        assertThat(newStage.getId()).isEqualTo(-1L);
        assertThat(newStage.getJobInstances().size()).isEqualTo(2);
        assertThat(newStage.isLatestRun()).isTrue();
        assertThat(newStage.getRerunOfCounter()).isEqualTo(2);

        newJava = newStage.getJobInstances().getByName("java");
        assertCopiedJob(newJava, 12L);
    }

    @Test
    void shouldCloneStageForGivenJobsWithLatestMd5() {
        TimeProvider timeProvider = new TimeProvider() {
            @Override
            public Date currentUtilDate() {
                return new Date();
            }
        };
        JobInstance firstJob = new JobInstance("first-job", timeProvider);
        JobInstance secondJob = new JobInstance("second-job", timeProvider);
        JobInstances jobInstances = new JobInstances(firstJob, secondJob);
        Stage stage = StageMother.custom("test", jobInstances);
        Stage clonedStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("first-job"), new DefaultSchedulingContext("loser", new Agents()),
            StageConfigMother.custom("test", "first-job", "second-job"),
            new TimeProvider(),
            "latest");
        assertThat(clonedStage.getConfigVersion()).isEqualTo("latest");
    }

    @Test
    void shouldAddEnvironmentVariablesPresentInTheScheduleContextToJobPlan() {
        JobConfig jobConfig = new JobConfig("foo");

        EnvironmentVariablesConfig variablesConfig = new EnvironmentVariablesConfig();
        variablesConfig.add("blahVar", "blahVal");
        SchedulingContext context = new DefaultSchedulingContext("Loser");
        context = context.overrideEnvironmentVariables(variablesConfig);
        JobPlan plan = instanceFactory.createJobPlan(jobConfig, context);
        assertThat(plan.getVariables()).contains(new EnvironmentVariable("blahVar", "blahVal"));
    }

    @Test
    void shouldOverrideEnvironmentVariablesPresentInTheScheduleContextToJobPlan() {
        EnvironmentVariablesConfig variablesConfig = new EnvironmentVariablesConfig();
        variablesConfig.add("blahVar", "blahVal");
        variablesConfig.add("differentVar", "differentVal");

        JobConfig jobConfig = new JobConfig("foo");
        jobConfig.setVariables(variablesConfig);

        EnvironmentVariablesConfig overridenConfig = new EnvironmentVariablesConfig();
        overridenConfig.add("blahVar", "originalVal");
        overridenConfig.add("secondVar", "secondVal");

        SchedulingContext context = new DefaultSchedulingContext();
        context = context.overrideEnvironmentVariables(overridenConfig);

        JobPlan plan = instanceFactory.createJobPlan(jobConfig, context);

        assertThat(plan.getVariables().size()).isEqualTo(3);
        assertThat(plan.getVariables()).contains(new EnvironmentVariable("blahVar", "blahVal"));
        assertThat(plan.getVariables()).contains(new EnvironmentVariable("secondVar", "secondVal"));
        assertThat(plan.getVariables()).contains(new EnvironmentVariable("differentVar", "differentVal"));
    }

    @Test
    void shouldAddEnvironmentVariablesToJobPlan() {
        EnvironmentVariablesConfig variablesConfig = new EnvironmentVariablesConfig();
        variablesConfig.add("blahVar", "blahVal");

        JobConfig jobConfig = new JobConfig("foo");
        jobConfig.setVariables(variablesConfig);

        SchedulingContext context = new DefaultSchedulingContext();

        JobPlan plan = instanceFactory.createJobPlan(jobConfig, context);

        assertThat(plan.getVariables()).contains(new EnvironmentVariable("blahVar", "blahVal"));
    }

    @Test
    void shouldCreateJobPlan() {
        ResourceConfigs resourceConfigs = new ResourceConfigs();
        ArtifactTypeConfigs artifactTypeConfigs = new ArtifactTypeConfigs();
        JobConfig jobConfig = new JobConfig(new CaseInsensitiveString("test"), resourceConfigs, artifactTypeConfigs);
        JobPlan plan = instanceFactory.createJobPlan(jobConfig, new DefaultSchedulingContext());
        assertThat(plan).isEqualTo(new DefaultJobPlan(new Resources(resourceConfigs), ArtifactPlan.toArtifactPlans(artifactTypeConfigs), -1, new JobIdentifier(), null, new EnvironmentVariables(), new EnvironmentVariables(), null, null));
    }


    @Test
    void shouldAddElasticProfileOnJobPlan() {
        ElasticProfile elasticProfile = new ElasticProfile("id", "prod-cluster");
        DefaultSchedulingContext context = new DefaultSchedulingContext("foo", new Agents(), Map.of("id", elasticProfile));

        ArtifactTypeConfigs artifactTypeConfigs = new ArtifactTypeConfigs();
        JobConfig jobConfig = new JobConfig(new CaseInsensitiveString("test"), null, artifactTypeConfigs);
        jobConfig.setElasticProfileId("id");
        JobPlan plan = instanceFactory.createJobPlan(jobConfig, context);

        assertThat(plan.getElasticProfile()).isEqualTo(elasticProfile);
        assertNull(plan.getClusterProfile());
    }

    @Test
    void shouldAddElasticProfileAndClusterProfileOnJobPlan() {
        ElasticProfile elasticProfile = new ElasticProfile("id", "clusterId");
        ClusterProfile clusterProfile = new ClusterProfile("clusterId", "pluginId");
        DefaultSchedulingContext context = new DefaultSchedulingContext("foo", new Agents(), Map.of("id", elasticProfile), Map.of("clusterId", clusterProfile));

        ArtifactTypeConfigs artifactTypeConfigs = new ArtifactTypeConfigs();
        JobConfig jobConfig = new JobConfig(new CaseInsensitiveString("test"), null, artifactTypeConfigs);
        jobConfig.setElasticProfileId("id");
        JobPlan plan = instanceFactory.createJobPlan(jobConfig, context);

        assertThat(plan.getElasticProfile()).isEqualTo(elasticProfile);
        assertThat(plan.getClusterProfile()).isEqualTo(clusterProfile);
    }

    @Test
    void shouldReturnBuildInstance() {
        ArtifactTypeConfigs artifactTypeConfigs = new ArtifactTypeConfigs();
        JobConfig jobConfig = new JobConfig(new CaseInsensitiveString("test"), null, artifactTypeConfigs);

        RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(jobConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("stage_foo"), jobConfig, new DefaultSchedulingContext(), new TimeProvider(), jobNameGenerator);

        JobInstance jobInstance = jobs.first();
        assertThat(jobConfig.name()).isEqualTo(new CaseInsensitiveString(jobInstance.getName()));
        assertThat(jobInstance.getState()).isEqualTo(JobState.Scheduled);
        assertThat(jobInstance.getScheduledDate()).isNotNull();
    }

    @Test
    void shouldUseRightNameGenerator() {
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java", "html");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunOnAllAgents(true);
        railsConfig.addResourceConfig("foobar");

        JobConfig javaConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("java"));
        javaConfig.setRunInstanceCount(2);

        Agent agent1 = new Agent("abcd1234", "host", "127.0.0.2", List.of("foobar"));
        Agent agent2 = new Agent("1234abcd", "ghost", "192.168.1.2", List.of("baz", "foobar"));
        Agent agent3 = new Agent("7890abdc", "lost", "10.4.3.55", List.of("crapyagent"));
        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents(agent1, agent2, agent3));

        Stage stageInstance = instanceFactory.createStageInstance(stageConfig, schedulingContext, "md5", clock);

        JobInstances jobInstances = stageInstance.getJobInstances();
        assertThat(jobInstances.size()).isEqualTo(5);
        assertRunOnAllAgentsJobInstance(jobInstances.get(0), "rails-runOnAll-1");
        assertRunOnAllAgentsJobInstance(jobInstances.get(1), "rails-runOnAll-2");
        assertRunMultipleJobInstance(jobInstances.get(2), "java-runInstance-1");
        assertRunMultipleJobInstance(jobInstances.get(3), "java-runInstance-2");
        assertSimpleJobInstance(jobInstances.get(4), "html");
    }

	/*
	SingleJobInstance
	 */

    @Test
    void shouldCreateASingleJobIfRunOnAllAgentsIsFalse() {
        JobConfig jobConfig = new JobConfig("foo");

        SchedulingContext context = mock(SchedulingContext.class);
        when(context.getEnvironmentVariablesConfig()).thenReturn(new EnvironmentVariablesConfig());
        when(context.overrideEnvironmentVariables(any(EnvironmentVariablesConfig.class))).thenReturn(context);

        RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(jobConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("someStage"), jobConfig, new DefaultSchedulingContext(), new TimeProvider(), jobNameGenerator);

        assertThat(jobs).satisfiesExactlyInAnyOrder(j -> {
            assertThat(j.getName()).isEqualTo("foo");
            assertThat(j.getAgentUuid()).isNull();
            assertThat(j.isRunOnAllAgents()).isFalse();
        });
    }

    @Test
    void shouldNotRerun_WhenJobConfigDoesNotExistAnymore_ForSingleInstanceJob() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        JobInstance rails = jobInstance(old, "rails", 7, 10);
        JobInstance java = jobInstance(old, "java", 12, 22);
        Stage stage = stage(9, rails, java);
        Stage newStage = null;

        CannotRerunJobException exception = null;
        try {
            newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails"), new DefaultSchedulingContext("loser", new Agents()), StageConfigMother.custom("dev", "java"), new TimeProvider(),
                "md5");
            fail("should not schedule when job config does not exist anymore");
        } catch (CannotRerunJobException e) {
            exception = e;
        }
        assertThat(exception.getJobName()).isEqualTo("rails");
        assertThat(newStage).isNull();
    }

    @Test
    void shouldClearAgentAssignment_ForSingleInstanceJobType() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        JobInstance rails = jobInstance(old, "rails", 7, 10);
        JobInstance java = jobInstance(old, "java", 12, 22);
        Stage stage = stage(9, rails, java);
        Stage newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails"), new DefaultSchedulingContext("loser", new Agents()), StageConfigMother.custom("dev", "rails", "java"),
            new TimeProvider(), "md5");
        assertThat(newStage.getJobInstances().getByName("rails").getAgentUuid()).isNull();
        assertThat(newStage.getJobInstances().getByName("java").getAgentUuid()).isNotNull();
    }

    @Test
    void shouldNotRerun_WhenJobConfigIsChangedToRunMultipleInstance_ForSingleJobInstance() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));

        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents());

        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), null);

        Stage stage = createStageInstance(old, jobs);
        Stage newStage = null;

        railsConfig.setRunInstanceCount(10);

        CannotRerunJobException exception = null;
        try {
            newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails"), schedulingContext, stageConfig, new TimeProvider(), "md5");
            fail("should not schedule since job config changed to run multiple instance");
        } catch (CannotRerunJobException e) {
            exception = e;
        }
        assertThat(exception.getJobName()).isEqualTo("rails");
        assertThat(exception.getInformation()).isEqualTo("Run configuration for job has been changed to 'run multiple instance'.");
        assertThat(newStage).isNull();
    }

	/*
	RunOnAllAgents tests
	*/

    @Test
    void shouldCreateAJobForEachAgentIfRunOnAllAgentsIsTrue() {
        Agents agents = new Agents();
        agents.add(new Agent("uuid1"));
        agents.add(new Agent("uuid2"));

        JobConfig jobConfig = new JobConfig("foo");
        jobConfig.setRunOnAllAgents(true);

        SchedulingContext context = mock(SchedulingContext.class);
        when(context.getApprovedBy()).thenReturn("chris");
        when(context.findAgentsMatching(new ResourceConfigs())).thenReturn(agents);
        when(context.getEnvironmentVariablesConfig()).thenReturn(new EnvironmentVariablesConfig());
        when(context.overrideEnvironmentVariables(any(EnvironmentVariablesConfig.class))).thenReturn(context);

        RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(jobConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("stageName"), jobConfig, context, new TimeProvider(), jobNameGenerator);

        assertThat(jobs).satisfiesExactlyInAnyOrder(j -> {
                assertThat(j.getName()).isEqualTo("foo-runOnAll-1");
                assertThat(j.getAgentUuid()).isEqualTo("uuid1");
                assertThat(j.isRunOnAllAgents()).isTrue();
            },
            j -> {
                assertThat(j.getName()).isEqualTo("foo-runOnAll-2");
                assertThat(j.getAgentUuid()).isEqualTo("uuid2");
                assertThat(j.isRunOnAllAgents()).isTrue();
            }
        );
    }

    @Test
    void shouldFailWhenDoesNotFindAnyMatchingAgents() {
        JobConfig jobConfig = new JobConfig("foo");
        jobConfig.setRunOnAllAgents(true);

        SchedulingContext context = mock(SchedulingContext.class);
        when(context.getApprovedBy()).thenReturn("chris");
        when(context.findAgentsMatching(new ResourceConfigs())).thenReturn(new ArrayList<>());
        when(context.getEnvironmentVariablesConfig()).thenReturn(new EnvironmentVariablesConfig());
        when(context.overrideEnvironmentVariables(any(EnvironmentVariablesConfig.class))).thenReturn(context);

        try {
            RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(jobConfig.name()));
            instanceFactory.createJobInstance(new CaseInsensitiveString("myStage"), jobConfig, new DefaultSchedulingContext(), new TimeProvider(), jobNameGenerator);

            fail("should have failed as no agents matched");
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Could not find matching agents to run job [foo] of stage [myStage].");
        }
    }

    @Test
    void shouldFailWhenNoAgentsmatchAJob() {
        DefaultSchedulingContext context = new DefaultSchedulingContext("raghu/vinay", new Agents());
        JobConfig fooJob = new JobConfig(new CaseInsensitiveString("foo"), new ResourceConfigs(), new ArtifactTypeConfigs());
        fooJob.setRunOnAllAgents(true);
        StageConfig stageConfig = new StageConfig(
            new CaseInsensitiveString("blah-stage"), new JobConfigs(
            fooJob,
            new JobConfig(new CaseInsensitiveString("bar"), new ResourceConfigs(), new ArtifactTypeConfigs())
        )
        );
        try {
            instanceFactory.createStageInstance(stageConfig, context, "md5", new TimeProvider());
            fail("expected exception but not thrown");
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("Could not find matching agents to run job [foo] of stage [blah-stage].");
        }
    }

    @Test
    void shouldBomb_ForRerun_OfASingleInstanceJobType_WhichWasEarlierRunOnAll_WithTwoRunOnAllInstancesSelectedForRerun() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunOnAllAgents(true);
        railsConfig.addResourceConfig("foobar");

        Agent agent1 = new Agent("abcd1234", "host", "127.0.0.2", List.of("foobar"));
        Agent agent2 = new Agent("1234abcd", "ghost", "192.168.1.2", List.of("baz", "foobar"));
        Agent agent3 = new Agent("7890abdc", "lost", "10.4.3.55", List.of("crapyagent"));
        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents(agent1, agent2, agent3));

        RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);

        Stage stage = createStageInstance(old, jobs);

        railsConfig.setRunOnAllAgents(false);

        try {
            instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runOnAll-1", "rails-runOnAll-2"), schedulingContext, stageConfig, new TimeProvider(), "md5");
            fail("should have failed when multiple run on all agents jobs are selected when job-config does not have run on all flag anymore");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).isEqualTo("Cannot schedule multiple instances of job named 'rails'.");
        }
    }

    @Test
    void should_NOT_ClearAgentAssignment_ForRerun_OfASingleInstanceJobType_WhichWasEarlierRunOnAll() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunOnAllAgents(true);
        railsConfig.addResourceConfig("foobar");

        Agent agent1 = new Agent("abcd1234", "host", "127.0.0.2", List.of("foobar"));
        Agent agent2 = new Agent("1234abcd", "ghost", "192.168.1.2", List.of("baz", "foobar"));
        Agent agent3 = new Agent("7890abdc", "lost", "10.4.3.55", List.of("crapyagent"));
        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents(agent1, agent2, agent3));

        RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);
        Stage stage = createStageInstance(old, jobs);

        railsConfig.setRunOnAllAgents(false);

        Stage newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runOnAll-1"), schedulingContext, stageConfig, new TimeProvider(), "md5");

        assertThat(newStage.getJobInstances().size()).isEqualTo(3);

        JobInstance newRailsJob = newStage.getJobInstances().getByName("rails");
        assertNewJob(old, newRailsJob);
        assertThat(newRailsJob.getAgentUuid()).isEqualTo("abcd1234");

        JobInstance copiedRailsJob = newStage.getJobInstances().getByName("rails-runOnAll-2");
        assertCopiedJob(copiedRailsJob, 102L);
        assertThat(copiedRailsJob.getAgentUuid()).isEqualTo("1234abcd");

        JobInstance copiedJavaJob = newStage.getJobInstances().getByName("java");
        assertCopiedJob(copiedJavaJob, 12L);
        assertThat(copiedJavaJob.getAgentUuid()).isNotNull();
    }

    @Test
    void shouldClearAgentAssignment_ForRunOnAllAgentsJobType() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        JobInstance rails = jobInstance(old, "rails", 7, 10);
        JobInstance java = jobInstance(old, "java", 12, 22);
        Stage stage = stage(9, rails, java);
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");
        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunOnAllAgents(true);
        railsConfig.addResourceConfig("foobar");

        Agent agent1 = new Agent("abcd1234", "host", "127.0.0.2", List.of("foobar"));
        Agent agent2 = new Agent("1234abcd", "ghost", "192.168.1.2", List.of("baz", "foobar"));
        Agent agent3 = new Agent("7890abdc", "lost", "10.4.3.55", List.of("crapyagent"));
        DefaultSchedulingContext context = new DefaultSchedulingContext("loser", new Agents(agent1, agent2, agent3));
        Stage newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails"), context, stageConfig, new TimeProvider(), "md5");

        assertThat(newStage.getJobInstances().size()).isEqualTo(3);

        JobInstance newRailsFirstJob = newStage.getJobInstances().getByName("rails-runOnAll-1");
        assertNewJob(old, newRailsFirstJob);
        assertThat(newRailsFirstJob.getAgentUuid()).isEqualTo("abcd1234");

        JobInstance newRailsSecondJob = newStage.getJobInstances().getByName("rails-runOnAll-2");
        assertNewJob(old, newRailsSecondJob);
        assertThat(newRailsSecondJob.getAgentUuid()).isEqualTo("1234abcd");

        JobInstance copiedJavaJob = newStage.getJobInstances().getByName("java");
        assertCopiedJob(copiedJavaJob, 12L);
        assertThat(copiedJavaJob.getAgentUuid()).isNotNull();
    }

    @Test
    void shouldNotRerun_WhenJobConfigDoesNotExistAnymore_ForRunOnAllAgentsJobInstance() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunOnAllAgents(true);
        railsConfig.addResourceConfig("foobar");

        Agent agent1 = new Agent("abcd1234", "host", "127.0.0.2", List.of("foobar"));
        Agent agent2 = new Agent("1234abcd", "ghost", "192.168.1.2", List.of("baz", "foobar"));
        Agent agent3 = new Agent("7890abdc", "lost", "10.4.3.55", List.of("crapyagent"));
        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents(agent1, agent2, agent3));

        RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);

        Stage stage = createStageInstance(old, jobs);
        Stage newStage = null;

        CannotRerunJobException exception = null;
        try {
            newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runOnAll-1"), new DefaultSchedulingContext("loser", new Agents()), StageConfigMother.custom("dev", "java"),
                new TimeProvider(), "md5");
            fail("should not schedule when job config does not exist anymore");
        } catch (CannotRerunJobException e) {
            exception = e;
        }
        assertThat(exception.getJobName()).isEqualTo("rails");
        assertThat(newStage).isNull();
    }

    @Test
    void shouldNotRerun_WhenJobConfigIsChangedToRunMultipleInstance_ForRunOnAllAgentsJobInstance() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunOnAllAgents(true);
        railsConfig.addResourceConfig("foobar");

        Agent agent1 = new Agent("abcd1234", "host", "127.0.0.2", List.of("foobar"));
        Agent agent2 = new Agent("1234abcd", "ghost", "192.168.1.2", List.of("baz", "foobar"));
        Agent agent3 = new Agent("7890abdc", "lost", "10.4.3.55", List.of("crapyagent"));
        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents(agent1, agent2, agent3));

        RunOnAllAgents.CounterBasedJobNameGenerator jobNameGenerator = new RunOnAllAgents.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);

        Stage stage = createStageInstance(old, jobs);
        Stage newStage = null;

        railsConfig.setRunOnAllAgents(false);
        railsConfig.setRunInstanceCount(10);

        CannotRerunJobException exception = null;
        try {
            newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runOnAll-1"), schedulingContext, stageConfig, new TimeProvider(), "md5");
            fail("should not schedule since job config changed to run multiple instance");
        } catch (CannotRerunJobException e) {
            exception = e;
        }
        assertThat(exception.getJobName()).isEqualTo("rails");
        assertThat(exception.getInformation()).isEqualTo("Run configuration for job has been changed to 'run multiple instance'.");
        assertThat(newStage).isNull();
    }

	/*
	RunMultipleInstance tests
	 */

    @Test
    void shouldCreateJobInstancesCorrectly_RunMultipleInstance() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunInstanceCount(3);

        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents());

        RunMultipleInstance.CounterBasedJobNameGenerator jobNameGenerator = new RunMultipleInstance.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);

        assertThat(jobs.get(0).getName()).isEqualTo("rails-runInstance-1");
        assertEnvironmentVariable(jobs.get(0), 0, "GO_JOB_RUN_INDEX", "1");
        assertEnvironmentVariable(jobs.get(0), 1, "GO_JOB_RUN_COUNT", "3");
        assertThat(jobs.get(1).getName()).isEqualTo("rails-runInstance-2");
        assertEnvironmentVariable(jobs.get(1), 0, "GO_JOB_RUN_INDEX", "2");
        assertEnvironmentVariable(jobs.get(1), 1, "GO_JOB_RUN_COUNT", "3");
        assertThat(jobs.get(2).getName()).isEqualTo("rails-runInstance-3");
        assertEnvironmentVariable(jobs.get(2), 0, "GO_JOB_RUN_INDEX", "3");
        assertEnvironmentVariable(jobs.get(2), 1, "GO_JOB_RUN_COUNT", "3");

        Stage stage = createStageInstance(old, jobs);

        JobInstances jobInstances = stage.getJobInstances();
        assertThat(jobInstances.size()).isEqualTo(4);
        assertRunMultipleJobInstance(jobInstances.get(0), "rails-runInstance-1");
        assertRunMultipleJobInstance(jobInstances.get(1), "rails-runInstance-2");
        assertRunMultipleJobInstance(jobInstances.get(2), "rails-runInstance-3");
        assertThat(jobInstances.get(3).getName()).isEqualTo("java");
    }

    @Test
    void shouldCreateJobInstancesCorrectly_RunMultipleInstance_Rerun() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunInstanceCount(3);

        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents());

        RunMultipleInstance.CounterBasedJobNameGenerator jobNameGenerator = new RunMultipleInstance.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);

        assertThat(jobs.get(0).getName()).isEqualTo("rails-runInstance-1");
        assertEnvironmentVariable(jobs.get(0), 0, "GO_JOB_RUN_INDEX", "1");
        assertEnvironmentVariable(jobs.get(0), 1, "GO_JOB_RUN_COUNT", "3");
        assertThat(jobs.get(1).getName()).isEqualTo("rails-runInstance-2");
        assertEnvironmentVariable(jobs.get(1), 0, "GO_JOB_RUN_INDEX", "2");
        assertEnvironmentVariable(jobs.get(1), 1, "GO_JOB_RUN_COUNT", "3");
        assertThat(jobs.get(2).getName()).isEqualTo("rails-runInstance-3");
        assertEnvironmentVariable(jobs.get(2), 0, "GO_JOB_RUN_INDEX", "3");
        assertEnvironmentVariable(jobs.get(2), 1, "GO_JOB_RUN_COUNT", "3");

        Stage stage = createStageInstance(old, jobs);

        Stage stageForRerun = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runInstance-1", "rails-runInstance-2"), schedulingContext, stageConfig, clock, "md5");
        JobInstances jobsForRerun = stageForRerun.getJobInstances();

        assertThat(jobsForRerun.get(0).getName()).isEqualTo("rails-runInstance-3");
        assertEnvironmentVariable(jobsForRerun.get(0), 0, "GO_JOB_RUN_INDEX", "3");
        assertEnvironmentVariable(jobsForRerun.get(0), 1, "GO_JOB_RUN_COUNT", "3");
        assertThat(jobsForRerun.get(2).getName()).isEqualTo("rails-runInstance-1");
        assertEnvironmentVariable(jobsForRerun.get(2), 0, "GO_JOB_RUN_INDEX", "1");
        assertEnvironmentVariable(jobsForRerun.get(2), 1, "GO_JOB_RUN_COUNT", "3");
        assertThat(jobsForRerun.get(3).getName()).isEqualTo("rails-runInstance-2");
        assertEnvironmentVariable(jobsForRerun.get(3), 0, "GO_JOB_RUN_INDEX", "2");
        assertEnvironmentVariable(jobsForRerun.get(3), 1, "GO_JOB_RUN_COUNT", "3");

        assertThat(jobsForRerun.size()).isEqualTo(4);
        assertRunMultipleJobInstance(jobsForRerun.get(0), "rails-runInstance-3");
        assertThat(jobsForRerun.get(1).getName()).isEqualTo("java");
        assertReRunMultipleJobInstance(jobsForRerun.get(2), "rails-runInstance-1");
        assertReRunMultipleJobInstance(jobsForRerun.get(3), "rails-runInstance-2");
    }

    @Test
    void shouldNotRerun_WhenJobConfigDoesNotExistAnymore_ForRunMultipleInstance() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunInstanceCount(3);

        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents());

        RunMultipleInstance.CounterBasedJobNameGenerator jobNameGenerator = new RunMultipleInstance.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);

        Stage stage = createStageInstance(old, jobs);
        Stage newStage = null;

        CannotRerunJobException exception = null;
        try {
            newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runInstance-1"), schedulingContext, StageConfigMother.custom("dev", "java"),
                new TimeProvider(), "md5");
            fail("should not schedule when job config does not exist anymore");
        } catch (CannotRerunJobException e) {
            exception = e;
        }
        assertThat(exception.getJobName()).isEqualTo("rails");
        assertThat(exception.getInformation()).isEqualTo("Configuration for job doesn't exist.");
        assertThat(newStage).isNull();
    }

    @Test
    void shouldNotRerun_WhenJobRunConfigIsChanged_ForRunMultipleInstance() {
        Date old = Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
        StageConfig stageConfig = StageConfigMother.custom("dev", "rails", "java");

        JobConfig railsConfig = stageConfig.getJobs().getJob(new CaseInsensitiveString("rails"));
        railsConfig.setRunInstanceCount(3);

        DefaultSchedulingContext schedulingContext = new DefaultSchedulingContext("loser", new Agents());

        RunMultipleInstance.CounterBasedJobNameGenerator jobNameGenerator = new RunMultipleInstance.CounterBasedJobNameGenerator(CaseInsensitiveString.str(railsConfig.name()));
        JobInstances jobs = instanceFactory.createJobInstance(new CaseInsensitiveString("dev"), railsConfig, schedulingContext, new TimeProvider(), jobNameGenerator);

        Stage stage = createStageInstance(old, jobs);
        Stage newStage = null;

        railsConfig.setRunOnAllAgents(true);
        railsConfig.setRunInstanceCount(0);

        CannotRerunJobException exception = null;
        try {
            newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runInstance-1"), schedulingContext, stageConfig, new TimeProvider(), "md5");
            fail("should not schedule since job config changed to run multiple instance");
        } catch (CannotRerunJobException e) {
            exception = e;
        }
        assertThat(exception.getJobName()).isEqualTo("rails");
        assertThat(exception.getInformation()).isEqualTo("Run configuration for job has been changed to 'run on all agents'.");
        assertThat(newStage).isNull();

        railsConfig.setRunOnAllAgents(false);

        try {
            newStage = instanceFactory.createStageForRerunOfJobs(stage, List.of("rails-runInstance-1"), schedulingContext, stageConfig, new TimeProvider(), "md5");
            fail("should not schedule since job config changed to run multiple instance");
        } catch (CannotRerunJobException e) {
            exception = e;
        }
        assertThat(exception.getJobName()).isEqualTo("rails");
        assertThat(exception.getInformation()).isEqualTo("Run configuration for job has been changed to 'simple'.");
        assertThat(newStage).isNull();
    }

    private Stage stage(long id, JobInstance... jobs) {
        Stage stage = new Stage("dev", new JobInstances(jobs), "anonymous", null, "manual", new TimeProvider());
        stage.setId(id);
        return stage;
    }

    private Stage createStageInstance(Date old, JobInstances jobs) {
        int jobId = 100;
        for (JobInstance job : jobs) {
            passJob(new Date(), ++jobId, jobId * 10, job);
        }

        JobInstance java = jobInstance(old, "java", 12, 22);
        jobs.add(java);

        return stage(9, jobs.toArray(new JobInstance[0]));
    }

    private JobInstance jobInstance(final Date date, final String jobName, final int id, int transitionIdStart) {
        JobInstance jobInstance = new JobInstance(jobName, new TimeProvider() {
            @Override
            public Date currentUtilDate() {
                return date;
            }
        });
        jobInstance.setAgentUuid(UUID.randomUUID().toString());
        return passJob(date, id, transitionIdStart, jobInstance);
    }

    private JobInstance passJob(Date date, int id, int transitionIdStart, JobInstance jobInstance) {
        jobInstance.setId(id);

        jobInstance.changeState(JobState.Completed, date);

        for (JobStateTransition jobStateTransition : jobInstance.getTransitions()) {
            jobStateTransition.setId(++transitionIdStart);
        }
        jobInstance.setResult(JobResult.Passed);

        return jobInstance;
    }

    private void assertCopiedJob(JobInstance newJava, final long originalId) {
        assertThat(newJava.getId()).isEqualTo(-1L);
        assertThat(newJava.getTransitions().isEmpty()).isFalse();
        assertThat(newJava.getResult()).isEqualTo(Passed);
        assertThat(newJava.getState()).isEqualTo(Completed);
        assertThat(newJava.getTransitions().byState(Scheduled).getId()).isEqualTo(-1L);
        assertThat(newJava.getTransitions().byState(Completed).getId()).isEqualTo(-1L);
        assertThat(newJava.getOriginalJobId()).isEqualTo(originalId);
        assertThat(newJava.isRerun()).isFalse();
        assertThat(newJava.isCopy()).isTrue();
    }

    private void assertNewJob(Date old, JobInstance newRails) {
        JobStateTransition newSchedulingTransition = assertNewJob(newRails);
        assertThat(newSchedulingTransition.getStateChangeTime().after(old)).isTrue();
    }

    private JobStateTransition assertNewJob(JobInstance newRails) {
        assertThat(newRails.getId()).isEqualTo(-1L);
        assertThat(newRails.getTransitions().size()).isEqualTo(1);
        JobStateTransition newSchedulingTransition = newRails.getTransitions().byState(JobState.Scheduled);
        assertThat(newSchedulingTransition.getId()).isEqualTo(-1L);
        assertThat(newRails.getResult()).isEqualTo(Unknown);
        assertThat(newRails.getState()).isEqualTo(Scheduled);
        assertThat(newRails.isRerun()).isTrue();
        return newSchedulingTransition;
    }

    private void assertSimpleJobInstance(JobInstance jobInstance, String jobName) {
        assertThat(jobInstance.getName()).isEqualTo(jobName);
        assertThat(jobInstance.isRunOnAllAgents()).isFalse();
        assertThat(jobInstance.isRunMultipleInstance()).isFalse();
        assertThat(jobInstance.isRerun()).isFalse();
    }

    private void assertRunOnAllAgentsJobInstance(JobInstance jobInstance, String jobName) {
        assertThat(jobInstance.getName()).isEqualTo(jobName);
        assertThat(jobInstance.isRunOnAllAgents()).isTrue();
        assertThat(jobInstance.isRunMultipleInstance()).isFalse();
        assertThat(jobInstance.isRerun()).isFalse();
    }

    private void assertRunMultipleJobInstance(JobInstance jobInstance, String jobName) {
        assertThat(jobInstance.getName()).isEqualTo(jobName);
        assertThat(jobInstance.isRunMultipleInstance()).isTrue();
        assertThat(jobInstance.isRunOnAllAgents()).isFalse();
        assertThat(jobInstance.isRerun()).isFalse();
    }

    private void assertReRunMultipleJobInstance(JobInstance jobInstance, String jobName) {
        assertThat(jobInstance.getName()).isEqualTo(jobName);
        assertThat(jobInstance.isRunMultipleInstance()).isTrue();
        assertThat(jobInstance.isRunOnAllAgents()).isFalse();
        assertThat(jobInstance.isRerun()).isTrue();
    }

    private void assertEnvironmentVariable(JobInstance jobInstance, int index, String name, String value) {
        assertThat(jobInstance.getPlan().getVariables().get(index).getName()).isEqualTo(name);
        assertThat(jobInstance.getPlan().getVariables().get(index).getValue()).isEqualTo(value);
    }
}
