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
package com.thoughtworks.go.server.dao;

import com.thoughtworks.go.domain.*;
import com.thoughtworks.go.helper.JobInstanceMother;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.domain.JobStatusListener;
import com.thoughtworks.go.server.transaction.SqlMapClientTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.thoughtworks.go.util.IBatisUtil.arguments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class JobInstanceSqlMapDaoCachingTest {
    @Autowired
    private GoCache goCache;
    @Autowired
    private JobInstanceSqlMapDao jobInstanceDao;
    private SqlMapClientTemplate mockTemplate;

    @BeforeEach
    public void setup() {
        mockTemplate = mock(SqlMapClientTemplate.class);
    }

    @AfterEach
    public void tearDown() {
        goCache.clear();
    }

    @Test
    public void buildByIdWithTransitions_shouldCacheWhenQueriedFor() {
        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);

        JobInstance job = JobInstanceMother.assigned("job");
        job.setId(1L);
        when(mockTemplate.queryForObject("buildByIdWithTransitions", 1L)).thenReturn(job);

        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(1L);
        assertThat(actual).isEqualTo(job);
        assertThat(actual == job).isFalse();

        jobInstanceDao.buildByIdWithTransitions(1L);
        verify(mockTemplate, times(1)).queryForObject("buildByIdWithTransitions", 1L);
    }

    @Test
    public void buildByIdWithTransitions_shouldClearFromCacheOnUpdateStatusOfJob() {
        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);

        JobInstance job = JobInstanceMother.assigned("job");
        job.setId(1L);
        when(mockTemplate.queryForObject("buildByIdWithTransitions", 1L)).thenReturn(job);

        JobInstance actual = jobInstanceDao.buildByIdWithTransitions(1L);
        assertThat(actual).isEqualTo(job);
        assertThat(actual == job).isFalse();

        jobInstanceDao.updateStateAndResult(job); //Must clear cahced job instance

        jobInstanceDao.buildByIdWithTransitions(1L);
        verify(mockTemplate, times(2)).queryForObject("buildByIdWithTransitions", 1L);
    }

    @Test
    public void orderedScheduledBuilds_shouldNotCacheJobPlanWhichIsNoLongerScheduled() {
        when(mockTemplate.queryForList(eq("scheduledPlanIds"))).thenReturn(List.of(1L, 2L));

        final DefaultJobPlan firstJob = jobPlan(1);
        when(mockTemplate.queryForObject("scheduledPlan", arguments("id", 1L).asMap())).thenReturn(firstJob);
        when(mockTemplate.queryForObject("scheduledPlan", arguments("id", 2L).asMap())).thenReturn(null);

        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);

        List<JobPlan> plans = jobInstanceDao.orderedScheduledBuilds();

        assertThat(plans).isEqualTo(List.of(firstJob));

        verify(mockTemplate, times(2)).queryForObject(eq("scheduledPlan"), any());
        verify(mockTemplate, times(1)).queryForList(eq("scheduledPlanIds"));
    }

    @Test
    public void orderedScheduledBuilds_shouldCacheJobPlan() {
        when(mockTemplate.queryForList(eq("scheduledPlanIds"))).thenReturn(List.of(1L, 2L));

        final DefaultJobPlan firstJob = jobPlan(1);
        final DefaultJobPlan secondJob = jobPlan(2);

        when(mockTemplate.queryForObject("scheduledPlan", arguments("id", 1L).asMap())).thenReturn(firstJob);
        when(mockTemplate.queryForObject("scheduledPlan", arguments("id", 2L).asMap())).thenReturn(secondJob);

        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);
        jobInstanceDao.orderedScheduledBuilds();

        List<JobPlan> plans = jobInstanceDao.orderedScheduledBuilds();

        assertThat(plans).isEqualTo(List.of(firstJob, secondJob));

        verify(mockTemplate, times(2)).queryForObject(eq("scheduledPlan"), any());
        verify(mockTemplate, times(2)).queryForList(eq("scheduledPlanIds"));
    }

    @Test
    public void updateStatus_shouldRemoveCachedJobPlan() {
        when(mockTemplate.queryForList(eq("scheduledPlanIds"))).thenReturn(List.of(1L));

        final DefaultJobPlan firstJob = jobPlan(1);
        when(mockTemplate.queryForObject("scheduledPlan", arguments("id", 1L).asMap())).thenReturn(firstJob);

        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);
        jobInstanceDao.orderedScheduledBuilds();//populate the cache

        JobInstance instance = instance(1);
        jobInstanceDao.updateStateAndResult(instance);

        List<JobPlan> plans = jobInstanceDao.orderedScheduledBuilds();

        assertThat(plans).isEqualTo(List.of(firstJob));

        verify(mockTemplate, times(2)).queryForObject("scheduledPlan", arguments("id", 1L).asMap());//because the cache is cleared
        verify(mockTemplate, times(2)).queryForList(eq("scheduledPlanIds"));
    }

    private JobInstance instance(long id) {
        JobInstance instance = new JobInstance("first");
        instance.setId(id);
        return instance;
    }

    @Test
    public void activeJobs_shouldCacheCurrentlyActiveJobIds() {
        final ActiveJob first = new ActiveJob(1L, "pipeline", 1, "label", "stage", "job1");
        final ActiveJob second = new ActiveJob(2L, "another", 2, "label", "stage", "job1");

        when(mockTemplate.queryForList("getActiveJobIds")).thenReturn(List.of(1L, 2L));
        when(mockTemplate.queryForObject("getActiveJobById", arguments("id", 1L).asMap())).thenReturn(first);
        when(mockTemplate.queryForObject("getActiveJobById", arguments("id", 2L).asMap())).thenReturn(second);

        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);
        jobInstanceDao.activeJobs();//populate the cache
        List<ActiveJob> activeJobs = jobInstanceDao.activeJobs();

        assertThat(activeJobs).isEqualTo(List.of(first, second));
        verify(mockTemplate, times(1)).queryForList("getActiveJobIds");
        verify(mockTemplate, times(1)).queryForObject("getActiveJobById", arguments("id", 1L).asMap());
        verify(mockTemplate, times(1)).queryForObject("getActiveJobById", arguments("id", 2L).asMap());
    }

    @Test
    public void activeJobs_shouldRemoveCacheActiveJobOnUpdateJobStatus() {
        final ActiveJob first = new ActiveJob(1L, "pipeline", 1, "label", "stage", "first");
        final ActiveJob second = new ActiveJob(2L, "another", 2, "label", "stage", "job1");

        when(mockTemplate.queryForList("getActiveJobIds")).thenReturn(List.of(1L, 2L));
        when(mockTemplate.queryForObject("getActiveJobById", arguments("id", 1L).asMap())).thenReturn(first);
        when(mockTemplate.queryForObject("getActiveJobById", arguments("id", 2L).asMap())).thenReturn(second);

        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);
        jobInstanceDao.activeJobs();//cache it first

        jobInstanceDao.updateStateAndResult(instance(1L));//should remove from cache

        List<ActiveJob> activeJobs = jobInstanceDao.activeJobs();

        assertThat(activeJobs).isEqualTo(List.of(first, second));

        verify(mockTemplate, times(2)).queryForList("getActiveJobIds");
        verify(mockTemplate, times(2)).queryForObject("getActiveJobById", arguments("id", 1L).asMap());
        verify(mockTemplate, times(1)).queryForObject("getActiveJobById", arguments("id", 2L).asMap());
    }

    @Test
    public void activeJobs_shouldNotCacheAJobThatsNoLongerActive() {
        final ActiveJob first = new ActiveJob(1L, "pipeline", 1, "label", "stage", "first");

        when(mockTemplate.queryForList("getActiveJobIds")).thenReturn(List.of(1L, 2L));
        when(mockTemplate.queryForObject("getActiveJobById", arguments("id", 1L).asMap())).thenReturn(first);
        when(mockTemplate.queryForObject("getActiveJobById", arguments("id", 2L).asMap())).thenReturn(null);

        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);
        jobInstanceDao.activeJobs();//cache it first

        jobInstanceDao.updateStateAndResult(instance(1L));//should remove from cache

        List<ActiveJob> activeJobs = jobInstanceDao.activeJobs();

        assertThat(activeJobs).isEqualTo(List.of(first));

        verify(mockTemplate, times(2)).queryForList("getActiveJobIds");
        verify(mockTemplate, times(2)).queryForObject("getActiveJobById", arguments("id", 1L).asMap());
    }

    @Test
    public void shouldCacheJobIdentifier() {
        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);

        JobInstance job = JobInstanceMother.buildEndingWithState(JobState.Building, JobResult.Unknown, "config");
        when(mockTemplate.queryForObject(eq("findJobId"), any(Map.class))).thenReturn(job.getIdentifier());

        jobInstanceDao.findOriginalJobIdentifier(job.getIdentifier().getStageIdentifier(), job.getName());
        jobInstanceDao.findOriginalJobIdentifier(job.getIdentifier().getStageIdentifier(), job.getName());

        verify(mockTemplate, times(1)).queryForObject(eq("findJobId"), any(Map.class));
    }

    @Test
    public void shouldClearJobIdentifierFromCacheWhenJobIsRescheduled() {
        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);

        JobInstance job = JobInstanceMother.buildEndingWithState(JobState.Building, JobResult.Unknown, "config");
        when(mockTemplate.queryForObject(eq("findJobId"), any(Map.class))).thenReturn(job.getIdentifier());

        jobInstanceDao.findOriginalJobIdentifier(job.getIdentifier().getStageIdentifier(), job.getName());

        job.changeState(JobState.Rescheduled, new Date());

        JobStatusListener listener = jobInstanceDao;
        listener.jobStatusChanged(job);

        jobInstanceDao.findOriginalJobIdentifier(job.getIdentifier().getStageIdentifier(), job.getName());

        verify(mockTemplate, times(2)).queryForObject(eq("findJobId"), any(Map.class));
    }

    @Test
    public void shouldNotClearJobIdentifierFromCacheForAnyOtherJobStateChangeOtherThanRescheduledAsTheBuildIdDoesNotChange() {
        jobInstanceDao.setSqlMapClientTemplate(mockTemplate);

        JobInstance job = JobInstanceMother.buildEndingWithState(JobState.Building, JobResult.Unknown, "config");
        when(mockTemplate.queryForObject(eq("findJobId"), any(Map.class))).thenReturn(job.getIdentifier());

        jobInstanceDao.findOriginalJobIdentifier(job.getIdentifier().getStageIdentifier(), job.getName());

        List<JobState> jobStatesForWhichCacheNeedsToBeMaintained = new ArrayList<>(List.of(JobState.Assigned, JobState.Building, JobState.Completed, JobState.Discontinued, JobState.Paused, JobState.Scheduled, JobState.Preparing, JobState.Unknown));

        JobStatusListener listener = jobInstanceDao;
        for (JobState jobState : jobStatesForWhichCacheNeedsToBeMaintained) {
            job.changeState(jobState, new Date());
            listener.jobStatusChanged(job);
        }

        jobInstanceDao.findOriginalJobIdentifier(job.getIdentifier().getStageIdentifier(), job.getName());

        verify(mockTemplate, times(1)).queryForObject(eq("findJobId"), any(Map.class));
    }

    private DefaultJobPlan jobPlan(long id) {
        return new DefaultJobPlan(new Resources(), new ArrayList<>(), id, null, null, new EnvironmentVariables(), new EnvironmentVariables(), null, null);
    }
}
