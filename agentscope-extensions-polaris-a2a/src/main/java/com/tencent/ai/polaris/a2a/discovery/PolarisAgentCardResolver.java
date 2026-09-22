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
import com.tencent.polaris.api.rpc.GetAllInstancesRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * AgentScope A2A card resolver backed by polaris-java.
 *
 * <p>Uses a pull model: {@link #getAgentCard(String)} looks up the cached card, and
 * on miss fetches all instances of the service named after the agent via
 * {@code ConsumerAPI.getAllInstances}, then reads the serialized card from the first
 * instance whose metadata carries {@link PolarisA2aConstants#META_AGENT_CARD}.
 *
 * <p>Push via {@code LocalRegistry.registerResourceListener} is a v2 enhancement; the
 * {@link AgentCardResolver} interface stays unchanged so the upgrade is transparent.
 */
public class PolarisAgentCardResolver implements AgentCardResolver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisAgentCardResolver.class);

    private final ConsumerAPI consumerAPI;
    private final String namespace;
    private final long refreshIntervalMs;
    private final LongSupplier currentTimeMillis;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PolarisAgentCardResolver(PolarisContextManager context) {
        this(context.consumerAPI(), context.getNamespace(), 0, System::currentTimeMillis);
    }

    PolarisAgentCardResolver(ConsumerAPI consumerAPI, String namespace) {
        this(consumerAPI, namespace, 0, System::currentTimeMillis);
    }

    PolarisAgentCardResolver(
            ConsumerAPI consumerAPI, String namespace, long refreshIntervalMs, LongSupplier currentTimeMillis) {
        this.consumerAPI = Objects.requireNonNull(consumerAPI, "consumerAPI");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.refreshIntervalMs = refreshIntervalMs;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        Objects.requireNonNull(agentName, "agentName");
        if (refreshIntervalMs <= 0) {
            return cache.computeIfAbsent(agentName, this::fetchEntry).card();
        }
        long now = currentTimeMillis.getAsLong();
        return cache.compute(agentName, (name, current) -> {
            if (current == null || now - current.fetchedAtMs() >= refreshIntervalMs) {
                AgentCard card = fetchFromPolaris(name);
                return new CacheEntry(card, currentTimeMillis.getAsLong());
            }
            return current;
        }).card();
    }

    private CacheEntry fetchEntry(String agentName) {
        return new CacheEntry(fetchFromPolaris(agentName), currentTimeMillis.getAsLong());
    }

    private AgentCard fetchFromPolaris(String agentName) {
        GetAllInstancesRequest req = GetAllInstancesRequest.builder()
                .namespace(namespace)
                .service(agentName)
                .build();
        InstancesResponse resp;
        try {
            resp = consumerAPI.getAllInstances(req);
        } catch (PolarisException e) {
            throw new AgentCardNotFoundException(agentName, e);
        }
        Instance[] instances = resp.getInstances();
        if (instances == null) {
            throw new AgentCardNotFoundException(agentName);
        }
        for (Instance inst : instances) {
            if (!inst.isHealthy()) {
                continue;
            }
            Map<String, String> metadata = inst.getMetadata();
            if (metadata == null) {
                continue;
            }
            String json = metadata.get(PolarisA2aConstants.META_AGENT_CARD);
            if (json != null) {
                AgentCard card;
                try {
                    card = AgentCardCodec.fromJson(json);
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping malformed agent card for '{}' from instance {}:{}",
                            agentName, inst.getHost(), inst.getPort(), e);
                    continue;
                }
                if (card.name() != null && !agentName.equals(card.name())) {
                    log.warn("Skipping agent card named '{}' while resolving '{}' from instance {}:{}",
                            card.name(), agentName, inst.getHost(), inst.getPort());
                    continue;
                }
                log.debug("Resolved agent '{}' card from instance {}:{}",
                        agentName, inst.getHost(), inst.getPort());
                return card;
            }
        }
        throw new AgentCardNotFoundException(agentName);
    }

    /** Drop the cached card so the next lookup re-fetches from polaris. */
    public void invalidate(String agentName) {
        cache.remove(agentName);
    }

    /** Clear the entire cache. */
    public void invalidateAll() {
        cache.clear();
    }

    @Override
    public void close() {
        cache.clear();
    }

    public static Builder builder(PolarisContextManager context) {
        return new Builder(context);
    }

    public static final class Builder {

        private final PolarisContextManager context;
        private long refreshIntervalMs;

        private Builder(PolarisContextManager context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        public Builder refreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
            return this;
        }

        public PolarisAgentCardResolver build() {
            return new PolarisAgentCardResolver(
                    context.consumerAPI(), context.getNamespace(), refreshIntervalMs, System::currentTimeMillis);
        }
    }

    private record CacheEntry(AgentCard card, long fetchedAtMs) {
    }
}
