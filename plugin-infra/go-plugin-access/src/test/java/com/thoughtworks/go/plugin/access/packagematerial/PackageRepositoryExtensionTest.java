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
package com.thoughtworks.go.plugin.access.packagematerial;

import com.thoughtworks.go.plugin.access.ExtensionsRegistry;
import com.thoughtworks.go.plugin.access.common.AbstractExtension;
import com.thoughtworks.go.plugin.access.common.settings.PluginSettingsConfiguration;
import com.thoughtworks.go.plugin.access.common.settings.PluginSettingsConstants;
import com.thoughtworks.go.plugin.access.common.settings.PluginSettingsJsonMessageHandler1_0;
import com.thoughtworks.go.plugin.api.config.Property;
import com.thoughtworks.go.plugin.api.material.packagerepository.PackageMaterialProperty;
import com.thoughtworks.go.plugin.api.material.packagerepository.PackageRevision;
import com.thoughtworks.go.plugin.api.material.packagerepository.RepositoryConfiguration;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.Result;
import com.thoughtworks.go.plugin.api.response.validation.ValidationError;
import com.thoughtworks.go.plugin.api.response.validation.ValidationResult;
import com.thoughtworks.go.plugin.infra.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.thoughtworks.go.plugin.domain.common.PluginConstants.PACKAGE_MATERIAL_EXTENSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PackageRepositoryExtensionTest {
    public static final String PLUGIN_ID = "plugin-id";
    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    @Mock(strictness = Mock.Strictness.LENIENT)
    private PluginManager pluginManager;
    @Mock
    private ExtensionsRegistry extensionsRegistry;
    @Mock
    private PluginSettingsJsonMessageHandler1_0 pluginSettingsJSONMessageHandler;
    @Mock
    private JsonMessageHandler1_0 jsonMessageHandler;
    private PackageRepositoryExtension extension;
    private PluginSettingsConfiguration pluginSettingsConfiguration;
    private RepositoryConfiguration repositoryConfiguration;
    private com.thoughtworks.go.plugin.api.material.packagerepository.PackageConfiguration packageConfiguration;
    private ArgumentCaptor<GoPluginApiRequest> requestArgumentCaptor;

    @BeforeEach
    public void setUp() {
        extension = new PackageRepositoryExtension(pluginManager, extensionsRegistry);

        pluginSettingsConfiguration = new PluginSettingsConfiguration();

        repositoryConfiguration = new RepositoryConfiguration();
        repositoryConfiguration.add(new PackageMaterialProperty("key-one", "value-one"));
        repositoryConfiguration.add(new PackageMaterialProperty("key-two", "value-two"));

        packageConfiguration = new com.thoughtworks.go.plugin.api.material.packagerepository.PackageConfiguration();
        packageConfiguration.add(new PackageMaterialProperty("key-three", "value-three"));
        packageConfiguration.add(new PackageMaterialProperty("key-four", "value-four"));

        requestArgumentCaptor = ArgumentCaptor.forClass(GoPluginApiRequest.class);
        when(pluginManager.resolveExtensionVersion(PLUGIN_ID, PACKAGE_MATERIAL_EXTENSION, List.of("1.0"))).thenReturn("1.0");
    }

    @Test
    public void shouldExtendAbstractExtension() {
        assertThat(extension).isInstanceOf(AbstractExtension.class);
    }

    @Test
    public void shouldTalkToPluginToGetPluginSettingsConfiguration() {
        extension.registerHandler("1.0", pluginSettingsJSONMessageHandler);
        extension.messageHandlerMap.put("1.0", jsonMessageHandler);

        String responseBody = "expected-response";
        PluginSettingsConfiguration deserializedResponse = new PluginSettingsConfiguration();
        when(pluginSettingsJSONMessageHandler.responseMessageForPluginSettingsConfiguration(responseBody)).thenReturn(deserializedResponse);

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        PluginSettingsConfiguration response = extension.getPluginSettingsConfiguration(PLUGIN_ID);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PluginSettingsConstants.REQUEST_PLUGIN_SETTINGS_CONFIGURATION, null);
        verify(pluginSettingsJSONMessageHandler).responseMessageForPluginSettingsConfiguration(responseBody);
        assertSame(response, deserializedResponse);
    }

    @Test
    public void shouldTalkToPluginToGetPluginSettingsView() {
        extension.registerHandler("1.0", pluginSettingsJSONMessageHandler);
        extension.messageHandlerMap.put("1.0", jsonMessageHandler);

        String responseBody = "expected-response";
        String deserializedResponse = "";
        when(pluginSettingsJSONMessageHandler.responseMessageForPluginSettingsView(responseBody)).thenReturn(deserializedResponse);

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        String response = extension.getPluginSettingsView(PLUGIN_ID);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PluginSettingsConstants.REQUEST_PLUGIN_SETTINGS_VIEW, null);
        verify(pluginSettingsJSONMessageHandler).responseMessageForPluginSettingsView(responseBody);
        assertSame(deserializedResponse, response);
    }

    @Test
    public void shouldTalkToPluginToValidatePluginSettings() {
        extension.registerHandler("1.0", pluginSettingsJSONMessageHandler);
        extension.messageHandlerMap.put("1.0", jsonMessageHandler);

        String requestBody = "expected-request";
        when(pluginSettingsJSONMessageHandler.requestMessageForPluginSettingsValidation(pluginSettingsConfiguration)).thenReturn(requestBody);
        String responseBody = "expected-response";
        ValidationResult deserializedResponse = new ValidationResult();
        when(pluginSettingsJSONMessageHandler.responseMessageForPluginSettingsValidation(responseBody)).thenReturn(deserializedResponse);

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(responseBody));

        ValidationResult response = extension.validatePluginSettings(PLUGIN_ID, pluginSettingsConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PluginSettingsConstants.REQUEST_VALIDATE_PLUGIN_SETTINGS, requestBody);
        verify(pluginSettingsJSONMessageHandler).responseMessageForPluginSettingsValidation(responseBody);
        assertSame(response, deserializedResponse);
    }

    @Test
    public void shouldTalkToPluginToGetRepositoryConfiguration() {
        String expectedRequestBody = null;

        String expectedResponseBody = "{" +
                "\"key-one\":{}," +
                "\"key-two\":{\"default-value\":\"two\",\"part-of-identity\":true,\"secure\":true,\"required\":true,\"display-name\":\"display-two\",\"display-order\":\"1\"}," +
                "\"key-three\":{\"default-value\":\"three\",\"part-of-identity\":false,\"secure\":false,\"required\":false,\"display-name\":\"display-three\",\"display-order\":\"2\"}" +
                "}";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        RepositoryConfiguration repositoryConfiguration = extension.getRepositoryConfiguration(PLUGIN_ID);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_REPOSITORY_CONFIGURATION, expectedRequestBody);
        assertPropertyConfiguration((PackageMaterialProperty) repositoryConfiguration.get("key-one"), "key-one", "", true, true, false, "", 0);
        assertPropertyConfiguration((PackageMaterialProperty) repositoryConfiguration.get("key-two"), "key-two", "two", true, true, true, "display-two", 1);
        assertPropertyConfiguration((PackageMaterialProperty) repositoryConfiguration.get("key-three"), "key-three", "three", false, false, false, "display-three", 2);
    }

    @Test
    public void shouldTalkToPluginToGetPackageConfiguration() {
        String expectedRequestBody = null;

        String expectedResponseBody = "{" +
                "\"key-one\":{}," +
                "\"key-two\":{\"default-value\":\"two\",\"part-of-identity\":true,\"secure\":true,\"required\":true,\"display-name\":\"display-two\",\"display-order\":\"1\"}," +
                "\"key-three\":{\"default-value\":\"three\",\"part-of-identity\":false,\"secure\":false,\"required\":false,\"display-name\":\"display-three\",\"display-order\":\"2\"}" +
                "}";
        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        com.thoughtworks.go.plugin.api.material.packagerepository.PackageConfiguration packageConfiguration = extension.getPackageConfiguration(PLUGIN_ID);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_PACKAGE_CONFIGURATION, expectedRequestBody);
        assertPropertyConfiguration((PackageMaterialProperty) packageConfiguration.get("key-one"), "key-one", "", true, true, false, "", 0);
        assertPropertyConfiguration((PackageMaterialProperty) packageConfiguration.get("key-two"), "key-two", "two", true, true, true, "display-two", 1);
        assertPropertyConfiguration((PackageMaterialProperty) packageConfiguration.get("key-three"), "key-three", "three", false, false, false, "display-three", 2);
    }

    @Test
    public void shouldTalkToPluginToCheckIfRepositoryConfigurationIsValid() {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}}";

        String expectedResponseBody = "[{\"key\":\"key-one\",\"message\":\"incorrect value\"},{\"message\":\"general error\"}]";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        ValidationResult validationResult = extension.isRepositoryConfigurationValid(PLUGIN_ID, repositoryConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_VALIDATE_REPOSITORY_CONFIGURATION, expectedRequestBody);
        assertValidationError(validationResult.getErrors().get(0), "key-one", "incorrect value");
        assertValidationError(validationResult.getErrors().get(1), "", "general error");
    }

    @Test
    public void shouldTalkToPluginToCheckIfPackageConfigurationIsValid() {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}," +
                "\"package-configuration\":{\"key-three\":{\"value\":\"value-three\"},\"key-four\":{\"value\":\"value-four\"}}}";

        String expectedResponseBody = "[{\"key\":\"key-one\",\"message\":\"incorrect value\"},{\"message\":\"general error\"}]";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        ValidationResult validationResult = extension.isPackageConfigurationValid(PLUGIN_ID, packageConfiguration, repositoryConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_VALIDATE_PACKAGE_CONFIGURATION, expectedRequestBody);
        assertValidationError(validationResult.getErrors().get(0), "key-one", "incorrect value");
        assertValidationError(validationResult.getErrors().get(1), "", "general error");
    }

    @Test
    public void shouldTalkToPluginToGetLatestModification() throws Exception {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}," +
                "\"package-configuration\":{\"key-three\":{\"value\":\"value-three\"},\"key-four\":{\"value\":\"value-four\"}}}";


        String expectedResponseBody = "{\"revision\":\"abc.rpm\",\"timestamp\":\"2011-07-14T19:43:37.100Z\",\"user\":\"some-user\",\"revisionComment\":\"comment\"," +
                "\"trackbackUrl\":\"http:\\\\localhost:9999\",\"data\":{\"dataKeyOne\":\"data-value-one\",\"dataKeyTwo\":\"data-value-two\"}}";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        PackageRevision packageRevision = extension.getLatestRevision(PLUGIN_ID, packageConfiguration, repositoryConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_LATEST_REVISION, expectedRequestBody);
        assertPackageRevision(packageRevision, "abc.rpm", "some-user", "2011-07-14T19:43:37.100Z", "comment", "http:\\localhost:9999");
    }

    @Test
    public void shouldTalkToPluginToGetLatestModificationSinceLastRevision() throws Exception {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}," +
                "\"package-configuration\":{\"key-three\":{\"value\":\"value-three\"},\"key-four\":{\"value\":\"value-four\"}}," +
                "\"previous-revision\":{\"revision\":\"abc.rpm\",\"timestamp\":\"2011-07-13T19:43:37.100Z\",\"data\":{\"dataKeyOne\":\"data-value-one\",\"dataKeyTwo\":\"data-value-two\"}}}";


        String expectedResponseBody = "{\"revision\":\"abc.rpm\",\"timestamp\":\"2011-07-14T19:43:37.100Z\",\"user\":\"some-user\",\"revisionComment\":\"comment\"," +
                "\"trackbackUrl\":\"http:\\\\localhost:9999\",\"data\":{\"dataKeyOne\":\"data-value-one\",\"dataKeyTwo\":\"data-value-two\"}}";

        Date timestamp = new SimpleDateFormat(DATE_FORMAT).parse("2011-07-13T19:43:37.100Z");
        Map<String, String> data = new LinkedHashMap<>();
        data.put("dataKeyOne", "data-value-one");
        data.put("dataKeyTwo", "data-value-two");
        PackageRevision previouslyKnownRevision = new PackageRevision("abc.rpm", timestamp, "someuser", "comment", null, data);

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        PackageRevision packageRevision = extension.latestModificationSince(PLUGIN_ID, packageConfiguration, repositoryConfiguration, previouslyKnownRevision);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_LATEST_REVISION_SINCE, expectedRequestBody);
        assertPackageRevision(packageRevision, "abc.rpm", "some-user", "2011-07-14T19:43:37.100Z", "comment", "http:\\localhost:9999");
    }

    @Test
    public void shouldTalkToPluginToCheckRepositoryConnectionSuccessful() {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}}";

        String expectedResponseBody = "{\"status\":\"success\",messages=[\"message-one\",\"message-two\"]}";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        Result result = extension.checkConnectionToRepository(PLUGIN_ID, repositoryConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_CHECK_REPOSITORY_CONNECTION, expectedRequestBody);
        assertSuccessResult(result, List.of("message-one", "message-two"));
    }

    @Test
    public void shouldTalkToPluginToCheckRepositoryConnectionFailure() {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}}";

        String expectedResponseBody = "{\"status\":\"failed\",messages=[\"message-one\",\"message-two\"]}";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        Result result = extension.checkConnectionToRepository(PLUGIN_ID, repositoryConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_CHECK_REPOSITORY_CONNECTION, expectedRequestBody);
        assertFailureResult(result, List.of("message-one", "message-two"));
    }

    @Test
    public void shouldTalkToPluginToCheckPackageConnectionSuccessful() {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}," +
                "\"package-configuration\":{\"key-three\":{\"value\":\"value-three\"},\"key-four\":{\"value\":\"value-four\"}}}";

        String expectedResponseBody = "{\"status\":\"success\",messages=[\"message-one\",\"message-two\"]}";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        Result result = extension.checkConnectionToPackage(PLUGIN_ID, packageConfiguration, repositoryConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_CHECK_PACKAGE_CONNECTION, expectedRequestBody);
        assertSuccessResult(result, List.of("message-one", "message-two"));
    }

    @Test
    public void shouldTalkToPluginToCheckPackageConnectionFailure() {
        String expectedRequestBody = "{\"repository-configuration\":{\"key-one\":{\"value\":\"value-one\"},\"key-two\":{\"value\":\"value-two\"}}," +
                "\"package-configuration\":{\"key-three\":{\"value\":\"value-three\"},\"key-four\":{\"value\":\"value-four\"}}}";

        String expectedResponseBody = "{\"status\":\"failure\",messages=[\"message-one\",\"message-two\"]}";

        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenReturn(DefaultGoPluginApiResponse.success(expectedResponseBody));

        Result result = extension.checkConnectionToPackage(PLUGIN_ID, packageConfiguration, repositoryConfiguration);

        assertRequest(requestArgumentCaptor.getValue(), PACKAGE_MATERIAL_EXTENSION, "1.0", PackageRepositoryExtension.REQUEST_CHECK_PACKAGE_CONNECTION, expectedRequestBody);
        assertFailureResult(result, List.of("message-one", "message-two"));
    }

    @Test
    public void shouldHandleExceptionDuringPluginInteraction() {
        when(pluginManager.isPluginOfType(PACKAGE_MATERIAL_EXTENSION, PLUGIN_ID)).thenReturn(true);
        when(pluginManager.submitTo(eq(PLUGIN_ID), eq(PACKAGE_MATERIAL_EXTENSION), requestArgumentCaptor.capture())).thenThrow(new RuntimeException("exception-from-plugin"));
        try {
            extension.checkConnectionToPackage(PLUGIN_ID, packageConfiguration, repositoryConfiguration);
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("exception-from-plugin");
        }
    }

    private void assertPropertyConfiguration(PackageMaterialProperty property, String key, String value, boolean partOfIdentity, boolean required, boolean secure, String displayName, int displayOrder) {
        assertThat(property.getKey()).isEqualTo(key);
        assertThat(property.getValue()).isEqualTo(value);
        assertThat(property.getOption(Property.PART_OF_IDENTITY)).isEqualTo(partOfIdentity);
        assertThat(property.getOption(Property.REQUIRED)).isEqualTo(required);
        assertThat(property.getOption(Property.SECURE)).isEqualTo(secure);
        assertThat(property.getOption(Property.DISPLAY_NAME)).isEqualTo(displayName);
        assertThat(property.getOption(Property.DISPLAY_ORDER)).isEqualTo(displayOrder);
    }

    private void assertRequest(GoPluginApiRequest goPluginApiRequest, String extensionName, String version, String requestName, String requestBody) {
        assertThat(goPluginApiRequest.extension()).isEqualTo(extensionName);
        assertThat(goPluginApiRequest.extensionVersion()).isEqualTo(version);
        assertThat(goPluginApiRequest.requestName()).isEqualTo(requestName);
        assertThat(goPluginApiRequest.requestBody()).isEqualTo(requestBody);
    }

    private void assertValidationError(ValidationError validationError, String expectedKey, String expectedMessage) {
        assertThat(validationError.getKey()).isEqualTo(expectedKey);
        assertThat(validationError.getMessage()).isEqualTo(expectedMessage);
    }

    private void assertPackageRevision(PackageRevision packageRevision, String revision, String user, String timestamp, String comment, String trackbackUrl) throws ParseException {
        assertThat(packageRevision.getRevision()).isEqualTo(revision);
        assertThat(packageRevision.getUser()).isEqualTo(user);
        assertThat(packageRevision.getTimestamp()).isEqualTo(new SimpleDateFormat(DATE_FORMAT).parse(timestamp));
        assertThat(packageRevision.getRevisionComment()).isEqualTo(comment);
        assertThat(packageRevision.getTrackbackUrl()).isEqualTo(trackbackUrl);
        assertThat(packageRevision.getData().size()).isEqualTo(2);
        assertThat(packageRevision.getDataFor("dataKeyOne")).isEqualTo("data-value-one");
        assertThat(packageRevision.getDataFor("dataKeyTwo")).isEqualTo("data-value-two");
    }

    private void assertSuccessResult(Result result, List<String> messages) {
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getMessages()).isEqualTo(messages);
    }

    private void assertFailureResult(Result result, List<String> messages) {
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getMessages()).isEqualTo(messages);
    }

}
