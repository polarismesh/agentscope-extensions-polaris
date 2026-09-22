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
import com.tencent.ai.polaris.a2a.exception.AgentCardNotFoundException;
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

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * AgentScope A2A card resolver backed by polaris-java.
 *
 * <p>Uses a pull model: {@link #getAgentCard(String)} looks up healthy, non-isolated
 * instances of the service named after the agent via {@code ConsumerAPI.getAllInstances},
 * reads {@link PolarisA2aConstants#META_AGENT_CARD_URL} from metadata, and HTTP-fetches
 * the AgentCard JSON.
 *
 * <p>Cache semantics for {@code refreshIntervalMs}:
 * <ul>
 *   <li>{@code >0} — TTL; refresh runs outside {@link ConcurrentHashMap} compute locks</li>
 *   <li>{@code 0} — fetch every call (no cache)</li>
 *   <li>{@code <0} — cache forever until {@link #invalidate(String)}</li>
 * </ul>
 * On TTL refresh failure the previous entry is kept. A true miss throws
 * {@link AgentCardNotFoundException}; Polaris SDK / network errors throw
 * {@link IllegalStateException}.
 */
public class PolarisAgentCardResolver implements AgentCardResolver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisAgentCardResolver.class);

    private final ConsumerAPI consumerAPI;
    private final String namespace;
    private final long refreshIntervalMs;
    private final LongSupplier currentTimeMillis;
    private final AgentCardHttpClient httpClient;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PolarisAgentCardResolver(PolarisContextManager context) {
        this(context.consumerAPI(), context.getNamespace(),
                PolarisA2aConstants.DEFAULT_REFRESH_INTERVAL_MS, System::currentTimeMillis,
                AgentCardHttpClient.jdkDefault());
    }

    PolarisAgentCardResolver(ConsumerAPI consumerAPI, String namespace) {
        this(consumerAPI, namespace, PolarisA2aConstants.DEFAULT_REFRESH_INTERVAL_MS,
                System::currentTimeMillis, AgentCardHttpClient.jdkDefault());
    }

    PolarisAgentCardResolver(
            ConsumerAPI consumerAPI,
            String namespace,
            long refreshIntervalMs,
            LongSupplier currentTimeMillis) {
        this(consumerAPI, namespace, refreshIntervalMs, currentTimeMillis, AgentCardHttpClient.jdkDefault());
    }

    PolarisAgentCardResolver(
            ConsumerAPI consumerAPI,
            String namespace,
            long refreshIntervalMs,
            LongSupplier currentTimeMillis,
            AgentCardHttpClient httpClient) {
        this.consumerAPI = Objects.requireNonNull(consumerAPI, "consumerAPI");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.refreshIntervalMs = refreshIntervalMs;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        Objects.requireNonNull(agentName, "agentName");
        if (refreshIntervalMs == 0) {
            return fetchFromPolaris(agentName);
        }
        if (refreshIntervalMs < 0) {
            return getOrLoadForever(agentName);
        }
        return getOrRefreshTtl(agentName);
    }

    private AgentCard getOrLoadForever(String agentName) {
        CacheEntry existing = cache.get(agentName);
        if (existing != null) {
            return existing.card();
        }
        AgentCard card = fetchFromPolaris(agentName);
        CacheEntry created = new CacheEntry(card, currentTimeMillis.getAsLong());
        CacheEntry raced = cache.putIfAbsent(agentName, created);
        return raced != null ? raced.card() : card;
    }

    private AgentCard getOrRefreshTtl(String agentName) {
        CacheEntry current = cache.get(agentName);
        long now = currentTimeMillis.getAsLong();
        if (current != null && now - current.fetchedAtMs() < refreshIntervalMs) {
            return current.card();
        }
        try {
            AgentCard card = fetchFromPolaris(agentName);
            cache.put(agentName, new CacheEntry(card, currentTimeMillis.getAsLong()));
            return card;
        } catch (RuntimeException e) {
            if (current != null) {
                log.warn("Failed to refresh AgentCard for '{}'; keeping cached entry: {}",
                        agentName, e.getMessage());
                return current.card();
            }
            throw e;
        }
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
            throw new IllegalStateException(
                    "Failed to discover agent '" + agentName + "' from polaris: " + e.getMessage(), e);
        }
        Instance[] instances = resp.getInstances();
        if (instances == null) {
            throw new AgentCardNotFoundException(agentName);
        }
        for (Instance inst : instances) {
            if (!inst.isHealthy() || inst.isIsolated()) {
                continue;
            }
            Map<String, String> metadata = inst.getMetadata();
            if (metadata == null) {
                continue;
            }
            String cardUrl = metadata.get(PolarisA2aConstants.META_AGENT_CARD_URL);
            if (cardUrl == null || cardUrl.isBlank()) {
                continue;
            }
            if (!isHttpUrl(cardUrl)) {
                log.warn("Skipping invalid agent card URL '{}' for '{}' from instance {}:{}",
                        cardUrl, agentName, inst.getHost(), inst.getPort());
                continue;
            }
            String json;
            try {
                json = httpClient.get(cardUrl);
            } catch (IOException e) {
                log.warn("Skipping agent '{}' card fetch from {} (instance {}:{}): {}",
                        agentName, cardUrl, inst.getHost(), inst.getPort(), e.getMessage());
                continue;
            }
            AgentCard card;
            try {
                card = AgentCardCodec.fromJson(json);
            } catch (IllegalArgumentException e) {
                log.warn("Skipping malformed agent card for '{}' from {}: {}",
                        agentName, cardUrl, e.getMessage());
                continue;
            }
            if (card.name() != null && !agentName.equals(card.name())) {
                log.warn("Skipping agent card named '{}' while resolving '{}' from {}",
                        card.name(), agentName, cardUrl);
                continue;
            }
            log.debug("Resolved agent '{}' card from {}", agentName, cardUrl);
            return card;
        }
        throw new AgentCardNotFoundException(agentName);
    }

    private static boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
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
        private long refreshIntervalMs = PolarisA2aConstants.DEFAULT_REFRESH_INTERVAL_MS;

        private Builder(PolarisContextManager context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        public Builder refreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
            return this;
        }

        public PolarisAgentCardResolver build() {
            return new PolarisAgentCardResolver(
                    context.consumerAPI(),
                    context.getNamespace(),
                    refreshIntervalMs,
                    System::currentTimeMillis,
                    AgentCardHttpClient.jdkDefault());
        }
    }

    private record CacheEntry(AgentCard card, long fetchedAtMs) {
    }
}
