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

import com.thoughtworks.go.config.ServerConfig;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.helper.StageMother;
import com.thoughtworks.go.server.service.result.DiskSpaceOperationResult;
import com.thoughtworks.go.server.service.result.HttpOperationResult;
import com.thoughtworks.go.server.service.result.OperationResult;
import com.thoughtworks.go.server.service.result.ServerHealthStateOperationResult;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.util.GoConstants;
import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.SystemEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class ArtifactsDiskCleanerTest {
    private SystemEnvironment sysEnv;
    private ArtifactsDiskCleaner artifactsDiskCleaner;
    private GoConfigService goConfigService;
    private SystemDiskSpaceChecker diskSpaceChecker;
    private ServerConfig serverConfig;
    private StageService stageService;
    private ArtifactsService artifactService;
    private ConfigDbStateRepository configDbStateRepository;
    private ServerHealthService serverHealthService;

    @BeforeEach
    public void setUp() {
        sysEnv = mock(SystemEnvironment.class);

        serverConfig = new ServerConfig();
        goConfigService = mock(GoConfigService.class);
        when(goConfigService.serverConfig()).thenReturn(serverConfig);

        stageService = mock(StageService.class);
        when(goConfigService.serverConfig()).thenReturn(serverConfig);

        artifactService = mock(ArtifactsService.class);

        diskSpaceChecker = mock(SystemDiskSpaceChecker.class);

        configDbStateRepository = mock(ConfigDbStateRepository.class);

        artifactsDiskCleaner = new ArtifactsDiskCleaner(sysEnv, goConfigService, diskSpaceChecker, artifactService, stageService, configDbStateRepository);
    }

    @Test
    public void shouldTriggerOnConfiguredPurgeStartLimit() {
        serverConfig.setPurgeLimits(null, null);
        assertThat(artifactsDiskCleaner.limitInMb()).isEqualTo(Long.valueOf(Integer.MAX_VALUE));
        serverConfig.setPurgeLimits(20.0, 30.0);
        assertThat(artifactsDiskCleaner.limitInMb()).isEqualTo(20 * GoConstants.MEGABYTES_IN_GIGABYTE);
        serverConfig.setPurgeLimits(15.0, 30.0);
        assertThat(artifactsDiskCleaner.limitInMb()).isEqualTo(15 * GoConstants.MEGABYTES_IN_GIGABYTE);
    }

    @Test
    @Timeout(20)
    public void shouldTriggerCleanupWhenLimitReached() throws InterruptedException {
        serverConfig.setPurgeLimits(20.0, 30.0);
        final boolean[] artifactsDeletionTriggered = {false};
        final Thread[] artifactDeleterThread = {null};
        final Semaphore sem = new Semaphore(1);
        sem.acquire();
        artifactsDiskCleaner = new ArtifactsDiskCleaner(sysEnv, goConfigService, diskSpaceChecker, artifactService, stageService, configDbStateRepository) {
            @Override void deleteOldArtifacts() {
                artifactDeleterThread[0] = Thread.currentThread();
                artifactsDeletionTriggered[0] = true;
                sem.release();
            }
        };
        Thread cleaner = ReflectionUtil.getField(artifactsDiskCleaner, "cleaner");
        while (!cleaner.getState().equals(Thread.State.WAITING)) {
            Thread.sleep(5);
        }
        artifactsDiskCleaner.createFailure(new HttpOperationResult(), 10, 100);
        sem.acquire();
        assertThat(artifactsDeletionTriggered[0]).isTrue();
        assertThat(artifactDeleterThread[0]).isNotSameAs(Thread.currentThread());
    }

    @Test
    public void shouldDeleteOldestStagesFirst_untilHasEnoughFreeDisk() {
        serverConfig.setPurgeLimits(5.0, 9.0);
        Stage stageOne = StageMother.passedStageInstance("stage", "build", "pipeline");
        Stage stageTwo = StageMother.passedStageInstance("another", "job", "with-pipeline");
        Stage stageThree = StageMother.passedStageInstance("yet-another", "job1", "foo-pipeline");

        when(stageService.oldestStagesWithDeletableArtifacts()).thenReturn(List.of(stageOne, stageTwo, stageThree));
        when(diskSpaceChecker.getUsableSpace(goConfigService.artifactsDir())).thenReturn(4 * GoConstants.GIGA_BYTE);

        doAnswer((Answer<Object>) invocation -> {
            when(diskSpaceChecker.getUsableSpace(goConfigService.artifactsDir())).thenReturn(6 * GoConstants.GIGA_BYTE);
            return null;
        }).when(artifactService).purgeArtifactsForStage(stageOne);

        doAnswer((Answer<Object>) invocation -> {
            when(diskSpaceChecker.getUsableSpace(goConfigService.artifactsDir())).thenReturn(10 * GoConstants.GIGA_BYTE);
            return null;
        }).when(artifactService).purgeArtifactsForStage(stageTwo);

        artifactsDiskCleaner.deleteOldArtifacts();

        verify(artifactService).purgeArtifactsForStage(stageOne);
        verify(artifactService).purgeArtifactsForStage(stageTwo);
        verify(configDbStateRepository).flushConfigState();
        verifyNoMoreInteractions(artifactService);
    }

    @Test
    public void shouldDeleteMultiplePagesOfOldestStagesHavingArtifacts() {
        serverConfig.setPurgeLimits(5.0, 9.0);
        final Stage stageOne = StageMother.passedStageInstance("stage", "build", "pipeline");
        final Stage stageTwo = StageMother.passedStageInstance("another", "job", "with-pipeline");
        final Stage stageThree = StageMother.passedStageInstance("yet-another", "job1", "foo-pipeline");
        final Stage stageFour = StageMother.passedStageInstance("foo-stage", "bar-job", "baz-pipeline");
        final Stage stageFive = StageMother.passedStageInstance("bar-stage", "baz-job", "quux-pipeline");

        when(stageService.oldestStagesWithDeletableArtifacts()).thenReturn(List.of(stageOne, stageTwo));
        when(diskSpaceChecker.getUsableSpace(goConfigService.artifactsDir())).thenReturn(4 * GoConstants.GIGA_BYTE);

        doAnswer((Answer<Object>) invocation -> {
            when(stageService.oldestStagesWithDeletableArtifacts()).thenReturn(List.of(stageThree, stageFour));
            return null;
        }).when(artifactService).purgeArtifactsForStage(stageTwo);

        doAnswer((Answer<Object>) invocation -> {
            when(stageService.oldestStagesWithDeletableArtifacts()).thenReturn(List.of(stageFive));
            return null;
        }).when(artifactService).purgeArtifactsForStage(stageFour);

        doAnswer((Answer<Object>) invocation -> {
            when(stageService.oldestStagesWithDeletableArtifacts()).thenReturn(new ArrayList<>());
            return null;
        }).when(artifactService).purgeArtifactsForStage(stageFive);

        artifactsDiskCleaner.deleteOldArtifacts();

        verify(artifactService).purgeArtifactsForStage(stageOne);
        verify(artifactService).purgeArtifactsForStage(stageTwo);
        verify(artifactService).purgeArtifactsForStage(stageThree);
        verify(artifactService).purgeArtifactsForStage(stageFour);
        verify(artifactService).purgeArtifactsForStage(stageFive);

        verify(stageService, times(4)).oldestStagesWithDeletableArtifacts();
        verify(configDbStateRepository, times(4)).flushConfigState();
        verifyNoMoreInteractions(artifactService);
        verifyNoMoreInteractions(stageService);
    }

    @Test
    public void shouldUseA_NonServerHealthAware_result() {
        serverHealthService = mock(ServerHealthService.class);
        OperationResult operationResult = artifactsDiskCleaner.resultFor(new DiskSpaceOperationResult(serverHealthService));
        assertThat(operationResult).isInstanceOf(ServerHealthStateOperationResult.class);
    }
}
