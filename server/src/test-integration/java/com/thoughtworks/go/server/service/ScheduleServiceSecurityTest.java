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

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.fixture.PipelineWithTwoStages;
import com.thoughtworks.go.server.dao.DatabaseAccessHelper;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.newsecurity.utils.SessionUtils;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import com.thoughtworks.go.server.transaction.TransactionTemplate;
import com.thoughtworks.go.util.GoConfigFileHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.nio.file.Path;

import static javax.servlet.http.HttpServletResponse.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {
        "classpath:/applicationContext-global.xml",
        "classpath:/applicationContext-dataLocalAccess.xml",
        "classpath:/testPropertyConfigurer.xml",
        "classpath:/spring-all-servlet.xml",
})
public class ScheduleServiceSecurityTest {
    @Autowired private GoConfigDao goConfigDao;
    @Autowired private ScheduleService scheduleService;
    @Autowired private DatabaseAccessHelper dbHelper;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private PipelineWithTwoStages fixture;
    private static final GoConfigFileHelper configHelper = new GoConfigFileHelper();

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        configHelper.onSetUp();
        configHelper.usingCruiseConfigDao(goConfigDao);

        dbHelper.onSetUp();
        fixture = new PipelineWithTwoStages(materialRepository, transactionTemplate, tempDir);
        fixture.usingConfigHelper(configHelper).usingDbHelper(dbHelper).onSetUp();
    }

    @AfterEach
    public void teardown() throws Exception {
        dbHelper.onTearDown();
        fixture.onTearDown();
    }

    @Test
    public void shouldReturnAppropriateHttpResultIfUserDoesNotHaveOperatePermission() throws Exception {
        configHelper.enableSecurity();
        configHelper.addAdmins("admin");
        configHelper.setOperatePermissionForGroup("defaultGroup", "jez");
        Pipeline pipeline = fixture.createPipelineWithFirstStagePassedAndSecondStageRunning();
        Username anonymous = new Username(new CaseInsensitiveString("anonymous"));
        HttpLocalizedOperationResult operationResult = new HttpLocalizedOperationResult();
        Stage resultStage = scheduleService.cancelAndTriggerRelevantStages(pipeline.getStages().byName(fixture.ftStage).getId(), anonymous, operationResult);

        assertThat(resultStage).isNull();
        assertThat(operationResult.isSuccessful()).isFalse();
        assertThat(operationResult.httpCode()).isEqualTo(SC_FORBIDDEN);
    }

    @Test
    public void shouldReturnAppropriateHttpResultIfTheStageIsInvalid() throws Exception {
        configHelper.enableSecurity();
        configHelper.setOperatePermissionForGroup("defaultGroup", "jez");
        Username jez = new Username(new CaseInsensitiveString("jez"));
        HttpLocalizedOperationResult operationResult = new HttpLocalizedOperationResult();
        Stage resultStage = scheduleService.cancelAndTriggerRelevantStages(-23L, jez, operationResult);

        assertThat(resultStage).isNull();
        assertThat(operationResult.isSuccessful()).isFalse();
        assertThat(operationResult.httpCode()).isEqualTo(SC_NOT_FOUND);
    }

    @Test
    public void shouldNotThrowExceptionIfUserHasOperatePermission() throws Exception {
        configHelper.enableSecurity();
        Username user = SessionUtils.currentUsername();
        configHelper.setOperatePermissionForGroup("defaultGroup", user.getUsername().toString());
        Pipeline pipeline = fixture.createPipelineWithFirstStagePassedAndSecondStageRunning();

        HttpLocalizedOperationResult operationResult = new HttpLocalizedOperationResult();

        Stage stageForCancellation = pipeline.getStages().byName(fixture.ftStage);
        Stage resultStage = scheduleService.cancelAndTriggerRelevantStages(stageForCancellation.getId(), user, operationResult);

        assertThat(resultStage).isNotNull();
        assertThat(operationResult.isSuccessful()).isTrue();
        assertThat(operationResult.httpCode()).isEqualTo(SC_OK);
    }

}
