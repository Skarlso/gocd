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
package com.thoughtworks.go.apiv2.backups.representers

import com.thoughtworks.go.apiv1.user.representers.UserSummaryRepresenter
import com.thoughtworks.go.server.domain.BackupProgressStatus
import com.thoughtworks.go.server.domain.BackupStatus
import com.thoughtworks.go.server.domain.ServerBackup
import org.junit.jupiter.api.Test

import static com.thoughtworks.go.CurrentGoCDVersion.apiDocsUrl
import static com.thoughtworks.go.api.base.JsonOutputWriter.jsonDate
import static com.thoughtworks.go.api.base.JsonUtils.toObject
import static com.thoughtworks.go.api.base.JsonUtils.toObjectString
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson

class BackupRepresenterTest {
  @Test
  void 'should serialize'() {
    def backup = new ServerBackup("/foo/bar", new Date(42), "bob", BackupStatus.IN_PROGRESS, "", 99)
    backup.setProgressStatus(BackupProgressStatus.BACKUP_CONFIG)

    def actualJson = toObjectString({ BackupRepresenter.toJSON(it, backup) })

    Map<String, Object> expectedJson = [
      _links           : [
        doc : [href: apiDocsUrl('#backups')],
        self: [href: "http://test.host/go/api/backups/99"]
      ],
      time             : jsonDate(new Date(42)),
      path             : "/foo/bar",
      user             : toObject({ UserSummaryRepresenter.toJSON(it, "bob") }),
      status           : 'IN_PROGRESS',
      "progress_status": 'BACKUP_CONFIG',
      message          : 'Backing up Configuration'
    ]

    assertThatJson(actualJson).isEqualTo(expectedJson)
  }

  @Test
  void 'should not serialize progress status when not present'() {
    def backup = new ServerBackup("/foo/bar", new Date(42), "bob", BackupStatus.COMPLETED, "exporting config", 99)

    def actualJson = toObjectString({ BackupRepresenter.toJSON(it, backup) })

    Map<String, Object> expectedJson = [
      _links : [
        doc : [href: apiDocsUrl('#backups')],
        self: [href: "http://test.host/go/api/backups/99"],
      ],
      time   : jsonDate(new Date(42)),
      path   : "/foo/bar",
      user   : toObject({ UserSummaryRepresenter.toJSON(it, "bob") }),
      status : 'COMPLETED',
      message: 'exporting config'
    ]

    assertThatJson(actualJson).isEqualTo(expectedJson)
  }
}
