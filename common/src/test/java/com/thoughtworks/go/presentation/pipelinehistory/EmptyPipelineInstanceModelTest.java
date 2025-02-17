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
package com.thoughtworks.go.presentation.pipelinehistory;

import com.thoughtworks.go.domain.PipelineIdentifier;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


public class EmptyPipelineInstanceModelTest {
    private EmptyPipelineInstanceModel instanceModel;

    @BeforeEach
    public void setUp() {
        instanceModel = PipelineInstanceModel.createEmptyPipelineInstanceModel("pipeline", BuildCause.createNeverRun(), new StageInstanceModels());
    }

    @Test
    public void shouldAdvertiseAsUnrealPipeline() {
        assertThat(instanceModel.hasHistoricalData()).isFalse();
    }

    @Test
    public void shouldReturnUnknownModificationAsCurrent() {
        assertThat(instanceModel.getCurrentRevision("foo")).isEqualTo(PipelineInstanceModel.UNKNOWN_REVISION);
    }

    @Test
    public void shouldBeCapableOfGeneratingPipelineIdentifier() {
        assertThat(instanceModel.getPipelineIdentifier()).isEqualTo(new PipelineIdentifier("pipeline", 0, "unknown"));
    }

    @Test
    public void shouldHaveNegetivePipelineId() {
        assertThat(instanceModel.getId()).isEqualTo(-1L);
    }
}
