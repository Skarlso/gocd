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
package com.thoughtworks.go.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DiskSpaceTest {

    @Test
    public void shouldCompareDiskSpace() {
        assertThat(new DiskSpace(10L).compareTo(new DiskSpace(12L))).isLessThan(0);
        assertThat(DiskSpace.unknownDiskSpace().compareTo(new DiskSpace(12L))).isLessThan(0);
        assertThat(new DiskSpace(10L).compareTo(DiskSpace.unknownDiskSpace())).isGreaterThan(0);
        assertThat(DiskSpace.unknownDiskSpace().compareTo(DiskSpace.unknownDiskSpace())).isEqualTo(0);
    }

    @Test
    public void shouldProduceHumanReadableStringRepresentation() {
        assertThat(new DiskSpace(3 * 512 * 1024 * 1024L).toString()).isEqualTo("1.5 GB");
        assertThat(new DiskSpace(10 * 1024 * 1024 * 1024L).toString()).isEqualTo("10.0 GB");
        assertThat(DiskSpace.unknownDiskSpace().toString()).isEqualTo("Unknown");
    }
}
