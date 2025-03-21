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

import com.thoughtworks.go.helper.PipelineInstanceModelMother;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PipelineHistoryItemTest {

    @Test
    public void shouldReturnFalseForEmptyPipelineHistory() {
        PipelineInstanceModel emptyOne = PipelineInstanceModel.createEmptyModel();
        assertThat(emptyOne.hasPreviousStageBeenScheduled("stage1")).isFalse();
    }

    @Test
    public void shouldReturnTrueForFirstStage() {
        assertThat(PipelineInstanceModelMother.custom("stage1").hasPreviousStageBeenScheduled("stage1")).isTrue();
        assertThat(PipelineInstanceModelMother.custom("stage1", "stage2").hasPreviousStageBeenScheduled("stage1")).isEqualTo(true);
    }

    @Test
    public void shouldCheckIfPreviousStageInstanceExist() {
        PipelineInstanceModel twoStages = PipelineInstanceModelMother.custom("stage1", "stage2");
        assertThat(twoStages.hasPreviousStageBeenScheduled("stage2")).isTrue();
    }

    @Test
    public void shouldReturnFalseIfPreviousStageHasNotBeenScheduled() {
        PipelineInstanceModel twoStages = PipelineInstanceModelMother.custom(new NullStageHistoryItem("stage1"),
                new StageInstanceModel("stage2", "1", new JobHistory()));
        assertThat(twoStages.hasPreviousStageBeenScheduled("stage2")).isFalse();
        PipelineInstanceModel threeStages = PipelineInstanceModelMother.custom(new NullStageHistoryItem("stage1"),
                new NullStageHistoryItem("stage2"),
                new StageInstanceModel("stage3", "1", new JobHistory()));
        assertThat(threeStages.hasPreviousStageBeenScheduled("stage3")).isFalse();
    }

}
