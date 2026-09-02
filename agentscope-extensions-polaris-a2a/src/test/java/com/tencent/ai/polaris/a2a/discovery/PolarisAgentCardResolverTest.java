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
import com.tencent.polaris.api.rpc.GetOneInstanceRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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

    @Captor
    private ArgumentCaptor<GetOneInstanceRequest> requestCaptor;

    private AgentCard buildCard(String name, String url) {
        return new AgentCard.Builder()
                .name(name)
                .description(name)
                .version("1.0.0")
                .url(url)
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private Instance buildInstance(String host, int port, Map<String, String> metadata) {
        Instance inst = mock(Instance.class);
        lenient().when(inst.getHost()).thenReturn(host);
        lenient().when(inst.getPort()).thenReturn(port);
        lenient().when(inst.getMetadata()).thenReturn(metadata);
        return inst;
    }

    private InstancesResponse responseWith(Instance instance) {
        InstancesResponse resp = mock(InstancesResponse.class);
        org.mockito.Mockito.doReturn(instance).when(resp).getInstance();
        return resp;
    }

    @Test
    void getAgentCard_usesLoadBalancedInstanceFromGetOneInstance() throws PolarisException {
        String cardJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(
                buildCard("weather-agent", "http://10.0.0.1:8080"));
        Instance picked = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(picked)).when(consumerAPI).getOneInstance(any(GetOneInstanceRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        AgentCard resolved = resolver.getAgentCard("weather-agent");

        verify(consumerAPI).getOneInstance(requestCaptor.capture());
        GetOneInstanceRequest req = requestCaptor.getValue();
        assertEquals(NAMESPACE, req.getNamespace());
        assertEquals("weather-agent", req.getService());
        assertEquals("weather-agent", resolved.name());
        assertEquals("http://10.0.0.1:8080", resolved.url());
    }

    @Test
    void getAgentCard_loadBalancesOnEachCall() throws PolarisException {
        String card1 = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(
                buildCard("echo", "http://10.0.0.1:8080"));
        String card2 = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(
                buildCard("echo", "http://10.0.0.2:8080"));
        Instance first = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", card1));
        Instance second = buildInstance("10.0.0.2", 8080, Map.of("a2a.agent.card", card2));
        InstancesResponse firstResp = responseWith(first);
        InstancesResponse secondResp = responseWith(second);
        when(consumerAPI.getOneInstance(any(GetOneInstanceRequest.class)))
                .thenReturn(firstResp, secondResp);

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        AgentCard firstResolved = resolver.getAgentCard("echo");
        AgentCard secondResolved = resolver.getAgentCard("echo");

        verify(consumerAPI, times(2)).getOneInstance(any(GetOneInstanceRequest.class));
        assertEquals("http://10.0.0.1:8080", firstResolved.url());
        assertEquals("http://10.0.0.2:8080", secondResolved.url());
    }

    @Test
    void getAgentCard_noCardMetadata_throwsNotFound() throws PolarisException {
        Instance picked = buildInstance("10.0.0.1", 8080, Map.of());
        doReturn(responseWith(picked)).when(consumerAPI).getOneInstance(any(GetOneInstanceRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("missing"));
    }

    @Test
    void getAgentCard_emptyInstance_throwsNotFound() throws PolarisException {
        doReturn(responseWith(null)).when(consumerAPI).getOneInstance(any(GetOneInstanceRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("missing"));
    }

    @Test
    void getAgentCard_polarisException_wrapsInNotFound() throws PolarisException {
        when(consumerAPI.getOneInstance(any(GetOneInstanceRequest.class)))
                .thenThrow(new PolarisException(ErrorCode.API_INVALID_ARGUMENT, "server down"));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("down"));
    }
}
