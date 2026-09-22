/*
 * Tencent is pleased to support the open source community by making agentscope-extensions-polaris available.
 *
 * Copyright (C) 2026 Tencent. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.ai.polaris.core;

import com.tencent.polaris.factory.config.ConfigurationImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolarisContextManagerTest {

    @Test
    void buildConfiguration_copiesAddressAndToken() {
        PolarisServerProperties properties = new PolarisServerProperties("10.0.0.1:8091,10.0.0.2:8091");
        properties.setToken("secret-token");

        ConfigurationImpl configuration = PolarisContextManager.buildConfiguration(properties);

        assertEquals(List.of("10.0.0.1:8091", "10.0.0.2:8091"),
                configuration.getGlobal().getServerConnector().getAddresses());
        assertEquals("secret-token", configuration.getGlobal().getServerConnector().getToken());
    }
}
