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

import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.ai.factory.SkillAPIFactory;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.core.ProviderAPI;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.client.api.SDKContext;
import com.tencent.polaris.factory.api.DiscoveryAPIFactory;
import com.tencent.polaris.factory.config.ConfigurationImpl;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class PolarisContextManagerTest {

    @Test
    void buildConfiguration_copiesAddressAndToken() {
        PolarisServerProperties properties = new PolarisServerProperties("10.0.0.1:8091,10.0.0.2:8091");
        properties.setToken("secret-token");

        ConfigurationImpl configuration = PolarisContextManager.buildConfiguration(properties);

        assertEquals(List.of("10.0.0.1:8091", "10.0.0.2:8091"),
                configuration.getGlobal().getServerConnector().getAddresses());
        assertEquals("secret-token", configuration.getGlobal().getServerConnector().getToken());
        assertEquals(List.of("10.0.0.1:8094", "10.0.0.2:8094"),
                configuration.getAi().getSkill().getServerConnector().getAddresses());
    }

    @Test
    void applySkillConnectorAddressesWritesAiSkillServerConnector() {
        PolarisServerProperties properties = new PolarisServerProperties("127.0.0.1:8091");
        properties.setSkillAddress("127.0.0.1:8094,127.0.0.2:8094");

        ConfigurationImpl configuration = PolarisContextManager.buildConfiguration(properties);

        assertEquals(List.of("127.0.0.1:8094", "127.0.0.2:8094"),
                configuration.getAi().getSkill().getServerConnector().getAddresses());
    }

    @Test
    void closeResources_closesAllResourcesAndSuppressesLaterFailures() {
        ProviderAPI providerAPI = mock(ProviderAPI.class);
        ConsumerAPI consumerAPI = mock(ConsumerAPI.class);
        SkillAPI skillAPI = mock(SkillAPI.class);
        SDKContext sdkContext = mock(SDKContext.class);
        RuntimeException providerFailure = new RuntimeException("provider close failed");
        RuntimeException consumerFailure = new RuntimeException("consumer close failed");
        RuntimeException skillFailure = new RuntimeException("skill close failed");
        doThrow(providerFailure).when(providerAPI).close();
        doThrow(consumerFailure).when(consumerAPI).close();
        doThrow(skillFailure).when(skillAPI).close();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> PolarisContextManager.closeResources(providerAPI, consumerAPI, skillAPI, sdkContext));

        assertSame(providerFailure, thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(consumerFailure, thrown.getSuppressed()[0]);
        assertSame(skillFailure, thrown.getSuppressed()[1]);
        InOrder closeOrder = inOrder(providerAPI, consumerAPI, skillAPI, sdkContext);
        closeOrder.verify(providerAPI).close();
        closeOrder.verify(consumerAPI).close();
        closeOrder.verify(skillAPI).close();
        closeOrder.verify(sdkContext).close();
    }

    @Test
    void closeResources_skipsResourcesThatWereNotInitialized() {
        ConsumerAPI consumerAPI = mock(ConsumerAPI.class);
        SDKContext sdkContext = mock(SDKContext.class);

        PolarisContextManager.closeResources(null, consumerAPI, null, sdkContext);

        InOrder closeOrder = inOrder(consumerAPI, sdkContext);
        closeOrder.verify(consumerAPI).close();
        closeOrder.verify(sdkContext).close();
    }

    @Test
    void closeResources_reportsSdkContextFailure() {
        SDKContext sdkContext = mock(SDKContext.class);
        RuntimeException sdkFailure = new RuntimeException("sdk close failed");
        doThrow(sdkFailure).when(sdkContext).close();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> PolarisContextManager.closeResources(null, null, null, sdkContext));

        assertSame(sdkFailure, thrown);
    }

    @Test
    void closeResources_doesNotSuppressTheSameFailureTwice() {
        ProviderAPI providerAPI = mock(ProviderAPI.class);
        ConsumerAPI consumerAPI = mock(ConsumerAPI.class);
        RuntimeException shared = new RuntimeException("same close failure");
        doThrow(shared).when(providerAPI).close();
        doThrow(shared).when(consumerAPI).close();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> PolarisContextManager.closeResources(providerAPI, consumerAPI, null, null));

        assertSame(shared, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void buildConfiguration_ignoresBlankToken() {
        PolarisServerProperties plain = new PolarisServerProperties("127.0.0.1:8091");
        PolarisServerProperties blank = new PolarisServerProperties("127.0.0.1:8091");
        blank.setToken("  ");

        assertEquals(
                PolarisContextManager.buildConfiguration(plain).getGlobal().getServerConnector().getToken(),
                PolarisContextManager.buildConfiguration(blank).getGlobal().getServerConnector().getToken());
    }

    @Test
    void buildConfiguration_rejectsEmptyAddressList() {
        PolarisServerProperties empty = new PolarisServerProperties("127.0.0.1:8091") {
            @Override
            public List<String> serverAddressList() {
                return List.of();
            }
        };
        PolarisServerProperties missing = new PolarisServerProperties("127.0.0.1:8091") {
            @Override
            public List<String> serverAddressList() {
                return null;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> PolarisContextManager.buildConfiguration(empty));
        assertThrows(IllegalArgumentException.class, () -> PolarisContextManager.buildConfiguration(missing));
        assertThrows(NullPointerException.class, () -> PolarisContextManager.buildConfiguration(null));
    }

    @Test
    void applySkillConnectorAddresses_fillsMissingAiSkillConnector() {
        ConfigurationImpl config = PolarisContextManager.buildConfiguration(
                new PolarisServerProperties("127.0.0.1:8091"));
        config.setAi(null);

        PolarisContextManager.applySkillConnectorAddresses(config, List.of("127.0.0.1:8094"));
        assertEquals(List.of("127.0.0.1:8094"),
                config.getAi().getSkill().getServerConnector().getAddresses());

        config.getAi().setSkill(null);
        PolarisContextManager.applySkillConnectorAddresses(config, List.of("10.0.0.1:8094"));
        assertEquals(List.of("10.0.0.1:8094"),
                config.getAi().getSkill().getServerConnector().getAddresses());

        config.getAi().getSkill().setServerConnector(null);
        PolarisContextManager.applySkillConnectorAddresses(config, List.of("10.0.0.2:8094"));
        assertEquals(List.of("10.0.0.2:8094"),
                config.getAi().getSkill().getServerConnector().getAddresses());
    }

    @Test
    void fromAddress_exposesApisAndClosesThem() {
        try (MockedStatic<SDKContext> sdk = mockStatic(SDKContext.class);
                MockedStatic<DiscoveryAPIFactory> discovery = mockStatic(DiscoveryAPIFactory.class);
                MockedStatic<SkillAPIFactory> skills = mockStatic(SkillAPIFactory.class)) {
            SDKContext context = mock(SDKContext.class);
            ProviderAPI provider = mock(ProviderAPI.class);
            ConsumerAPI consumer = mock(ConsumerAPI.class);
            SkillAPI skill = mock(SkillAPI.class);
            sdk.when(() -> SDKContext.initContextByConfig(any())).thenReturn(context);
            discovery.when(() -> DiscoveryAPIFactory.createProviderAPIByContext(context)).thenReturn(provider);
            discovery.when(() -> DiscoveryAPIFactory.createConsumerAPIByContext(context)).thenReturn(consumer);
            skills.when(() -> SkillAPIFactory.createSkillAPIByContext(context)).thenReturn(skill);

            PolarisContextManager manager = PolarisContextManager.fromAddress("127.0.0.1:8091");
            assertEquals("127.0.0.1:8091", manager.getProperties().getAddress());
            assertNull(manager.getProperties().getSkillAddress());
            assertEquals(List.of("127.0.0.1:8094"), manager.getProperties().skillAddressList());
            assertSame(context, manager.getSdkContext());
            assertSame(provider, manager.providerAPI());
            assertSame(consumer, manager.consumerAPI());
            assertSame(skill, manager.skillAPI());
            assertEquals("default", manager.getNamespace());

            PolarisServerProperties custom = new PolarisServerProperties("10.0.0.1:8091");
            custom.setNamespace("prod");
            custom.setToken("secret");
            PolarisContextManager named = new PolarisContextManager(custom);
            assertEquals("prod", named.getNamespace());

            PolarisContextManager explicit =
                    PolarisContextManager.fromAddress("10.0.0.2:8091", "10.0.0.9:8094");
            assertEquals("10.0.0.9:8094", explicit.getProperties().getSkillAddress());

            PolarisContextManager multi =
                    PolarisContextManager.fromAddress(List.of("10.0.0.3:8091", "10.0.0.4:8091"));
            assertEquals("10.0.0.3:8091,10.0.0.4:8091", multi.getProperties().getAddress());

            manager.close();
            InOrder closeOrder = inOrder(provider, consumer, skill, context);
            closeOrder.verify(provider).close();
            closeOrder.verify(consumer).close();
            closeOrder.verify(skill).close();
            closeOrder.verify(context).close();
        }
    }

    @Test
    void constructor_closesPartialResourcesWhenApiCreationFails() {
        try (MockedStatic<SDKContext> sdk = mockStatic(SDKContext.class);
                MockedStatic<DiscoveryAPIFactory> discovery = mockStatic(DiscoveryAPIFactory.class);
                MockedStatic<SkillAPIFactory> skills = mockStatic(SkillAPIFactory.class)) {
            SDKContext context = mock(SDKContext.class);
            sdk.when(() -> SDKContext.initContextByConfig(any())).thenReturn(context);
            discovery.when(() -> DiscoveryAPIFactory.createProviderAPIByContext(context))
                    .thenThrow(new PolarisException(ErrorCode.NETWORK_ERROR, "provider down"));

            IllegalStateException providerFailure = assertThrows(IllegalStateException.class,
                    () -> PolarisContextManager.fromAddress("127.0.0.1:8091"));
            assertTrue(providerFailure.getMessage().contains("ProviderAPI"));
            verify(context).close();

            ProviderAPI provider = mock(ProviderAPI.class);
            ConsumerAPI consumer = mock(ConsumerAPI.class);
            discovery.when(() -> DiscoveryAPIFactory.createProviderAPIByContext(context)).thenReturn(provider);
            discovery.when(() -> DiscoveryAPIFactory.createConsumerAPIByContext(context)).thenReturn(consumer);
            skills.when(() -> SkillAPIFactory.createSkillAPIByContext(context))
                    .thenThrow(new PolarisException(ErrorCode.NETWORK_ERROR, "skill down"));

            IllegalStateException skillFailure = assertThrows(IllegalStateException.class,
                    () -> new PolarisContextManager(new PolarisServerProperties("127.0.0.1:8091")));
            assertTrue(skillFailure.getMessage().contains("SkillAPI"));
            verify(provider).close();
            verify(consumer).close();

            sdk.when(() -> SDKContext.initContextByConfig(any()))
                    .thenThrow(new PolarisException(ErrorCode.NETWORK_ERROR, "context down"));
            IllegalStateException contextFailure = assertThrows(IllegalStateException.class,
                    () -> PolarisContextManager.fromAddress(List.of("127.0.0.1:8091")));
            assertTrue(contextFailure.getMessage().contains("SDKContext"));
        }
    }
}
