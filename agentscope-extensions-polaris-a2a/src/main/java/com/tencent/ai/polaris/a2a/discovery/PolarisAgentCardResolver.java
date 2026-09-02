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

import com.tencent.ai.polaris.a2a.constant.PolarisA2aConstants;
import com.tencent.ai.polaris.a2a.util.AgentCardCodec;
import com.tencent.ai.polaris.core.PolarisContextManager;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.rpc.GetOneInstanceRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * AgentScope A2A card resolver backed by polaris-java.
 *
 * <p>Each {@link #getAgentCard(String)} call uses {@code ConsumerAPI.getOneInstance} so
 * Polaris routing and load balancing pick one instance, then reads the serialized
 * {@link AgentCard} from that instance's {@link PolarisA2aConstants#META_AGENT_CARD}
 * metadata. Cards are not cached locally; {@link io.agentscope.core.a2a.agent.A2aAgent}
 * rebuilds the A2A client on every call and will therefore load-balance across replicas.
 */
public class PolarisAgentCardResolver implements AgentCardResolver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisAgentCardResolver.class);

    private final ConsumerAPI consumerAPI;
    private final String namespace;

    public PolarisAgentCardResolver(PolarisContextManager context) {
        this(context.consumerAPI(), context.getNamespace());
    }

    PolarisAgentCardResolver(ConsumerAPI consumerAPI, String namespace) {
        this.consumerAPI = Objects.requireNonNull(consumerAPI, "consumerAPI");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        Objects.requireNonNull(agentName, "agentName");
        return fetchFromPolaris(agentName);
    }

    private AgentCard fetchFromPolaris(String agentName) {
        GetOneInstanceRequest req = new GetOneInstanceRequest();
        req.setNamespace(namespace);
        req.setService(agentName);
        InstancesResponse resp;
        try {
            resp = consumerAPI.getOneInstance(req);
        } catch (PolarisException e) {
            throw new AgentCardNotFoundException(agentName, e);
        }
        Instance inst = resp == null ? null : resp.getInstance();
        if (inst == null) {
            throw new AgentCardNotFoundException(agentName);
        }
        Map<String, String> metadata = inst.getMetadata();
        if (metadata != null) {
            String json = metadata.get(PolarisA2aConstants.META_AGENT_CARD);
            if (json != null) {
                AgentCard card = AgentCardCodec.fromJson(json);
                log.debug("Resolved agent '{}' card from instance {}:{}",
                        agentName, inst.getHost(), inst.getPort());
                return card;
            }
        }
        throw new AgentCardNotFoundException(agentName);
    }

    @Override
    public void close() {
        // no local cache to drop; each getAgentCard hits Polaris getOneInstance
    }

    public static Builder builder(PolarisContextManager context) {
        return new Builder(context);
    }

    public static final class Builder {

        private final PolarisContextManager context;

        private Builder(PolarisContextManager context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        public PolarisAgentCardResolver build() {
            return new PolarisAgentCardResolver(context.consumerAPI(), context.getNamespace());
        }
    }
}
