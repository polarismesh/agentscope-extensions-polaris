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

package com.tencent.ai.polaris.a2a.discovery;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.rpc.GetAllInstancesRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolarisAgentCardResolverTest {

    private static final String NAMESPACE = "default";

    @Mock
    private ConsumerAPI consumerAPI;

    private AgentCard buildCard(String name) {
        return new AgentCard.Builder()
                .name(name)
                .description(name)
                .version("1.0.0")
                .url("http://localhost:8080")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private Instance buildInstance(String host, int port, boolean healthy, Map<String, String> metadata) {
        Instance inst = mock(Instance.class);
        lenient().when(inst.getHost()).thenReturn(host);
        lenient().when(inst.getPort()).thenReturn(port);
        lenient().when(inst.isHealthy()).thenReturn(healthy);
        lenient().when(inst.getMetadata()).thenReturn(metadata);
        return inst;
    }

    private InstancesResponse responseWith(Instance... instances) {
        InstancesResponse resp = mock(InstancesResponse.class);
        lenient().when(resp.getInstances()).thenReturn(instances);
        return resp;
    }

    @Test
    void getAgentCard_resolvesFromHealthyInstanceMetadata() throws PolarisException {
        String cardJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("weather-agent"));
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        AgentCard resolved = resolver.getAgentCard("weather-agent");

        assertEquals("weather-agent", resolved.name());
        assertEquals("1.0.0", resolved.version());
    }

    @Test
    void getAgentCard_skipsUnhealthyInstances() throws PolarisException {
        String cardJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("echo"));
        Instance unhealthy = buildInstance("10.0.0.2", 8081, false, Map.of("a2a.agent.card", cardJson));
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(unhealthy, healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        AgentCard resolved = resolver.getAgentCard("echo");

        assertEquals("echo", resolved.name());
    }

    @Test
    void getAgentCard_cachesAfterFirstFetch() throws PolarisException {
        String cardJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("cached"));
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        resolver.getAgentCard("cached");
        resolver.getAgentCard("cached");

        verify(consumerAPI, times(1)).getAllInstances(any(GetAllInstancesRequest.class));
    }

    @Test
    void getAgentCard_refetchesAfterRefreshInterval() throws PolarisException {
        String firstJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("refreshable"));
        String secondJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(
                new AgentCard.Builder()
                        .name("refreshable")
                        .description("updated")
                        .version("2.0.0")
                        .url("http://localhost:8080")
                        .capabilities(new AgentCapabilities(false, false, false, List.of()))
                        .defaultInputModes(List.of("text"))
                        .defaultOutputModes(List.of("text"))
                        .skills(List.of())
                        .build());
        Instance first = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", firstJson));
        Instance second = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", secondJson));
        InstancesResponse firstResponse = responseWith(first);
        InstancesResponse secondResponse = responseWith(second);
        when(consumerAPI.getAllInstances(any(GetAllInstancesRequest.class)))
                .thenReturn(firstResponse, secondResponse);
        AtomicLong now = new AtomicLong();
        PolarisAgentCardResolver resolver =
                new PolarisAgentCardResolver(consumerAPI, NAMESPACE, 100, now::get);

        assertEquals("1.0.0", resolver.getAgentCard("refreshable").version());
        now.set(99);
        assertEquals("1.0.0", resolver.getAgentCard("refreshable").version());
        now.set(100);
        assertEquals("2.0.0", resolver.getAgentCard("refreshable").version());

        verify(consumerAPI, times(2)).getAllInstances(any(GetAllInstancesRequest.class));
    }

    @Test
    void getAgentCard_skipsMalformedCardAndUsesNextValidInstance() throws PolarisException {
        String validJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("healthy"));
        Instance malformed = buildInstance("10.0.0.1", 8080, true,
                Map.of("a2a.agent.card", "{not-json"));
        Instance valid = buildInstance("10.0.0.2", 8080, true,
                Map.of("a2a.agent.card", validJson));
        doReturn(responseWith(malformed, valid))
                .when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);

        assertEquals("healthy", resolver.getAgentCard("healthy").name());
    }

    @Test
    void getAgentCard_skipsCardWithDifferentName() throws PolarisException {
        String wrongJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("other"));
        Instance wrong = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", wrongJson));
        doReturn(responseWith(wrong)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);

        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("requested"));
    }

    @Test
    void getAgentCard_onlyMalformedMetadata_throwsNotFound() throws PolarisException {
        Instance malformed = buildInstance("10.0.0.1", 8080, true,
                Map.of("a2a.agent.card", "{not-json"));
        doReturn(responseWith(malformed)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);

        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("missing"));
    }

    @Test
    void getAgentCard_noInstanceWithCard_throwsNotFound() throws PolarisException {
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of());
        doReturn(responseWith(healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("missing"));
    }

    @Test
    void getAgentCard_emptyInstances_throwsNotFound() throws PolarisException {
        doReturn(responseWith()).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("missing"));
    }

    @Test
    void getAgentCard_polarisException_wrapsInNotFound() throws PolarisException {
        when(consumerAPI.getAllInstances(any(GetAllInstancesRequest.class)))
                .thenThrow(new PolarisException(ErrorCode.API_INVALID_ARGUMENT, "server down"));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("down"));
    }
}
