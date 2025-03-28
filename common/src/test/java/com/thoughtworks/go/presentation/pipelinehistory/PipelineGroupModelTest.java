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

import com.thoughtworks.go.domain.PipelinePauseInfo;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.junit.jupiter.api.Test;

import static org.apache.commons.lang3.builder.ToStringBuilder.reflectionToString;
import static org.assertj.core.api.Assertions.assertThat;

public class PipelineGroupModelTest {

    @Test
    public void shouldSayContainsPipelineIrrespectiveOfPipelineNameCase() {
        PipelineGroupModel groupModel = new PipelineGroupModel("group");
        groupModel.add(pipelineModel("pipeline"));
        assertThat(groupModel.containsPipeline("PIPELINE")).isTrue();
    }

    @Test
    public void shouldCopyAllInternalsOfPipelineModelWhenCreatingANewOneIfNeeded() {
        PipelineGroupModel groupModel = new PipelineGroupModel("group");

        PipelineModel expectedModel = addInstanceTo(new PipelineModel("p1", true, true, PipelinePauseInfo.notPaused()));
        expectedModel.updateAdministrability(true);

        groupModel.add(expectedModel);
        PipelineModel actualModel = groupModel.getPipelineModel("p1");

        assertThat(EqualsBuilder.reflectionEquals(actualModel, expectedModel))
            .describedAs(String.format("\nExpected: %s\nActual:   %s", reflectionToString(expectedModel), reflectionToString(actualModel)))
            .isTrue();
    }

    private PipelineModel pipelineModel(String pipelineName) {
        PipelineModel pipelineModel = new PipelineModel(pipelineName, true, true, PipelinePauseInfo.notPaused());
        return addInstanceTo(pipelineModel);
    }

    private PipelineModel addInstanceTo(PipelineModel pipelineModel) {
        pipelineModel.addPipelineInstance(new PipelineInstanceModel(pipelineModel.getName(), 1, "label", BuildCause.createManualForced(), new StageInstanceModels()));
        return pipelineModel;
    }
}
