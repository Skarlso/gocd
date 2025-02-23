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
package com.thoughtworks.go.config.crud;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.GoConfigHolder;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.exceptions.GoConfigInvalidException;
import com.thoughtworks.go.config.materials.Filter;
import com.thoughtworks.go.config.materials.IgnoredFiles;
import com.thoughtworks.go.config.materials.PluggableSCMMaterialConfig;
import com.thoughtworks.go.domain.config.Configuration;
import com.thoughtworks.go.domain.scm.SCM;
import com.thoughtworks.go.plugin.access.scm.*;
import com.thoughtworks.go.security.GoCipher;
import com.thoughtworks.go.util.GoConstants;
import com.thoughtworks.go.util.XsdValidationException;
import org.junit.jupiter.api.Test;

import static com.thoughtworks.go.plugin.api.config.Property.*;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class SCMConfigXmlLoaderTest extends AbstractConfigXmlLoaderTest {
    final static String VALID_SCM = " <scm id='scm-id' name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";
    final static String VALID_SCM_WITH_ID_NAME = " <scm id='%s' name='%s'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";
    final static String SCM_WITH_MISSING_ID = " <scm name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";
    final static String SCM_WITH_INVALID_ID = " <scm id='id with space' name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";
    final static String SCM_WITH_EMPTY_ID = " <scm id='' name='name1'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";
    final static String SCM_WITH_MISSING_NAME = " <scm id='id' ><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";
    final static String SCM_WITH_INVALID_NAME = " <scm id='id' name='name with space'><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";
    final static String SCM_WITH_EMPTY_NAME = " <scm id='id' name=''><pluginConfiguration id='id' version='1.0'/><configuration><property><key>url</key><value>http://go</value></property></configuration></scm>";

    @Test
    public void shouldThrowXsdValidationWhenSCMIdsAreDuplicate() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + VALID_SCM + VALID_SCM + " </scms></cruise>";
        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown XsdValidationException");
        } catch (XsdValidationException e) {
            assertThat(e.getMessage()).containsAnyOf(
                "Duplicate unique value [scm-id] declared for identity constraint of element \"cruise\".",
                "Duplicate unique value [scm-id] declared for identity constraint \"uniqueSCMId\" of element \"cruise\"."
            );
        }
    }

    @Test
    public void shouldThrowXsdValidationWhenSCMIdIsEmpty() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + SCM_WITH_EMPTY_ID + " </scms></cruise>";
        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown XsdValidationException");
        } catch (XsdValidationException e) {
            assertThat(e.getMessage()).isEqualTo("Scm id is invalid. \"\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
        }
    }

    @Test
    public void shouldThrowXsdValidationWhenSCMIdIsInvalid() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + SCM_WITH_INVALID_ID + " </scms></cruise>";
        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown XsdValidationException");
        } catch (XsdValidationException e) {
            assertThat(e.getMessage()).isEqualTo("Scm id is invalid. \"id with space\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
        }
    }

    @Test
    public void shouldThrowXsdValidationWhenSCMNamesAreDuplicate() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + format(VALID_SCM_WITH_ID_NAME, "1", "scm-name") + format(VALID_SCM_WITH_ID_NAME, "2", "scm-name") + " </scms></cruise>";
        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown XsdValidationException");
        } catch (XsdValidationException e) {
            assertThat(e.getMessage()).containsAnyOf(("Duplicate unique value [scm-name] declared for identity constraint of element \"scms\"."), "Duplicate unique value [scm-name] declared for identity constraint \"uniqueSCMName\" of element \"scms\"."
                    );
        }
    }

    @Test
    public void shouldThrowXsdValidationWhenSCMNameIsMissing() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + SCM_WITH_MISSING_NAME + " </scms></cruise>";
        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown XsdValidationException");
        } catch (XsdValidationException e) {
            assertThat(e.getMessage()).isEqualTo("\"Name\" is required for Scm");
        }
    }

    @Test
    public void shouldThrowXsdValidationWhenSCMNameIsEmpty() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + SCM_WITH_EMPTY_NAME + " </scms></cruise>";
        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown XsdValidationException");
        } catch (XsdValidationException e) {
            assertThat(e.getMessage()).isEqualTo("Name is invalid. \"\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
        }
    }

    @Test
    public void shouldThrowXsdValidationWhenSCMNameIsInvalid() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + SCM_WITH_INVALID_NAME + " </scms></cruise>";
        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown XsdValidationException");
        } catch (XsdValidationException e) {
            assertThat(e.getMessage()).isEqualTo("Name is invalid. \"name with space\" should conform to the pattern - [a-zA-Z0-9_\\-]{1}[a-zA-Z0-9_\\-.]*");
        }
    }

    @Test
    public void shouldGenerateSCMIdWhenMissing() throws Exception {
        String xml = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'><scms>\n" + SCM_WITH_MISSING_ID + " </scms></cruise>";

        GoConfigHolder configHolder = xmlLoader.loadConfigHolder(xml);

        assertThat(configHolder.config.getSCMs().get(0).getId()).isNotNull();
    }

    @Test
    public void shouldFailValidationIfSCMWithDuplicateFingerprintExists() throws Exception {
        SCMPropertyConfiguration scmConfiguration = new SCMPropertyConfiguration();
        scmConfiguration.add(new SCMProperty("SCM-KEY1"));
        scmConfiguration.add(new SCMProperty("SCM-KEY2").with(REQUIRED, false).with(PART_OF_IDENTITY, false));
        scmConfiguration.add(new SCMProperty("SCM-KEY3").with(REQUIRED, false).with(PART_OF_IDENTITY, false).with(SECURE, true));
        SCMMetadataStore.getInstance().addMetadataFor("plugin-1", new SCMConfigurations(scmConfiguration), null);

        String xml = ("""
            <cruise schemaVersion='%d'>
            <scms>
                <scm id='scm-id-1' name='name-1'>
                <pluginConfiguration id='plugin-1' version='1.0'/>
                  <configuration>
                    <property>
                      <key>SCM-KEY1</key>
                      <value>scm-key1</value>
                    </property>
                    <property>
                      <key>SCM-KEY2</key>
                      <value>scm-key2</value>
                    </property>
                    <property>
                      <key>SCM-KEY3</key>
                      <value>scm-key3</value>
                    </property>
                  </configuration>
                </scm>
                <scm id='scm-id-2' name='name-2'>
                <pluginConfiguration id='plugin-1' version='1.0'/>
                  <configuration>
                    <property>
                      <key>SCM-KEY1</key>
                      <value>scm-key1</value>
                    </property>
                    <property>
                      <key>SCM-KEY2</key>
                      <value>another-scm-key2</value>
                    </property>
                    <property>
                      <key>SCM-KEY3</key>
                      <value>another-scm-key3</value>
                    </property>
                  </configuration>
                </scm>
              </scms>
            </cruise>""").formatted(GoConstants.CONFIG_SCHEMA_VERSION);

        try {
            xmlLoader.loadConfigHolder(xml);
            fail("should have thrown duplicate fingerprint exception");
        } catch (GoConfigInvalidException e) {
            assertThat(e.getMessage()).isEqualTo("Cannot save SCM, found duplicate SCMs. name-1, name-2");
        }
    }

    @Test
    public void shouldLoadAutoUpdateValueForSCMWhenLoadedFromConfigFile() throws Exception {
        String configTemplate = ("""
            <cruise schemaVersion='%d'>
            <scms>
              <scm id='2ef830d7-dd66-42d6-b393-64a84646e557' name='scm-name' autoUpdate='%%s' >
                <pluginConfiguration id='yum' version='1' />
                   <configuration>
                       <property>
                           <key>SCM_URL</key>
                           <value>http://fake-scm/git/go-cd</value>
                           </property>
                   </configuration>
               </scm>
            </scms>
            </cruise>""").formatted(GoConstants.CONFIG_SCHEMA_VERSION);
        String configContent = String.format(configTemplate, false);
        GoConfigHolder holder = xmlLoader.loadConfigHolder(configContent);
        SCM scm = holder.config.getSCMs().find("2ef830d7-dd66-42d6-b393-64a84646e557");
        assertThat(scm.isAutoUpdate()).isFalse();

        configContent = String.format(configTemplate, true);
        holder = xmlLoader.loadConfigHolder(configContent);
        scm = holder.config.getSCMs().find("2ef830d7-dd66-42d6-b393-64a84646e557");
        assertThat(scm.isAutoUpdate()).isTrue();
    }

    @Test
    public void shouldResolveSCMReferenceElementForAMaterialInConfig() throws Exception {
        String xml = ("""
            <cruise schemaVersion='%d'>
            <scms>
                <scm id='scm-id' name='scm-name'>
                <pluginConfiguration id='plugin-id' version='1.0'/>
                  <configuration>
                    <property>
                      <key>url</key>
                      <value>http://go</value>
                    </property>
                  </configuration>
                </scm>
              </scms>
            <pipelines group="group_name">
              <pipeline name="new_name">
                <materials>
                  <scm ref='scm-id' />
                </materials>
                <stage name="stage_name">
                  <jobs>
                    <job name="job_name">
                       <tasks><ant /></tasks>
                    </job>
                  </jobs>
                </stage>
              </pipeline>
            </pipelines></cruise>""").formatted(GoConstants.CONFIG_SCHEMA_VERSION);

        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(xml);
        PipelineConfig pipelineConfig = goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("new_name"));
        PluggableSCMMaterialConfig pluggableSCMMaterialConfig = (PluggableSCMMaterialConfig) pipelineConfig.materialConfigs().get(0);
        assertThat(pluggableSCMMaterialConfig.getSCMConfig()).isEqualTo(goConfigHolder.config.getSCMs().get(0));
        assertThat(pluggableSCMMaterialConfig.getFolder()).isNull();
        assertThat(pluggableSCMMaterialConfig.filter()).isEqualTo(new Filter());
    }

    @Test
    public void shouldReadFolderAndFilterForPluggableSCMMaterialConfig() throws Exception {
        String xml = ("""
            <cruise schemaVersion='%d'>
            <scms>
                <scm id='scm-id' name='scm-name'>
                <pluginConfiguration id='plugin-id' version='1.0'/>
                  <configuration>
                    <property>
                      <key>url</key>
                      <value>http://go</value>
                    </property>
                  </configuration>
                </scm>
              </scms>
            <pipelines group="group_name">
              <pipeline name="new_name">
                <materials>
                  <scm ref='scm-id' dest='dest'>
                        <filter>
                            <ignore pattern="x"/>
                            <ignore pattern="y"/>
                        </filter>
                  </scm>
                </materials>
                <stage name="stage_name">
                  <jobs>
                    <job name="job_name">
                       <tasks><ant /></tasks>
                    </job>
                  </jobs>
                </stage>
              </pipeline>
            </pipelines></cruise>""").formatted(GoConstants.CONFIG_SCHEMA_VERSION);

        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(xml);
        PipelineConfig pipelineConfig = goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("new_name"));
        PluggableSCMMaterialConfig pluggableSCMMaterialConfig = (PluggableSCMMaterialConfig) pipelineConfig.materialConfigs().get(0);
        assertThat(pluggableSCMMaterialConfig.getSCMConfig()).isEqualTo(goConfigHolder.config.getSCMs().get(0));
        assertThat(pluggableSCMMaterialConfig.getFolder()).isEqualTo("dest");
        assertThat(pluggableSCMMaterialConfig.filter()).isEqualTo(new Filter(new IgnoredFiles("x"), new IgnoredFiles("y")));
    }

    @Test
    public void shouldBeAbleToResolveSecureConfigPropertiesForSCMs() throws Exception {
        String encryptedValue = new GoCipher().encrypt("secure-two");
        String xml = ("""
            <cruise schemaVersion='%d'>
            <scms>
                <scm id='scm-id' name='name'>
                <pluginConfiguration id='plugin-id' version='1.0'/>
                  <configuration>
                    <property>
                      <key>plain</key>
                      <value>value</value>
                    </property>
                    <property>
                      <key>secure-one</key>
                      <value>secure-value</value>
                    </property>
                    <property>
                      <key>secure-two</key>
                      <encryptedValue>%s</encryptedValue>
                    </property>
                  </configuration>
                </scm>
              </scms>
            <pipelines group="group_name">
              <pipeline name="new_name">
                <materials>
                  <scm ref='scm-id' />
                </materials>
                <stage name="stage_name">
                  <jobs>
                    <job name="job_name">
                       <tasks><ant /></tasks>
                    </job>
                  </jobs>
                </stage>
              </pipeline>
            </pipelines></cruise>""").formatted(GoConstants.CONFIG_SCHEMA_VERSION, encryptedValue);

        //meta data of scm
        SCMPropertyConfiguration scmConfiguration = new SCMPropertyConfiguration();
        scmConfiguration.add(new SCMProperty("plain"));
        scmConfiguration.add(new SCMProperty("secure-one").with(SCMConfiguration.SECURE, true));
        scmConfiguration.add(new SCMProperty("secure-two").with(SCMConfiguration.SECURE, true));
        SCMMetadataStore.getInstance().addMetadataFor("plugin-id", new SCMConfigurations(scmConfiguration), null);

        GoConfigHolder goConfigHolder = xmlLoader.loadConfigHolder(xml);
        SCM scmConfig = goConfigHolder.config.getSCMs().first();
        PipelineConfig pipelineConfig = goConfigHolder.config.pipelineConfigByName(new CaseInsensitiveString("new_name"));
        PluggableSCMMaterialConfig pluggableSCMMaterialConfig = (PluggableSCMMaterialConfig) pipelineConfig.materialConfigs().get(0);
        assertThat(pluggableSCMMaterialConfig.getSCMConfig()).isEqualTo(scmConfig);
        Configuration configuration = pluggableSCMMaterialConfig.getSCMConfig().getConfiguration();
        assertThat(configuration.get(0).getConfigurationValue().getValue()).isEqualTo("value");
        assertThat(configuration.get(1).getEncryptedValue()).isEqualTo(new GoCipher().encrypt("secure-value"));
        assertThat(configuration.get(2).getEncryptedValue()).isEqualTo(encryptedValue);
    }
}
