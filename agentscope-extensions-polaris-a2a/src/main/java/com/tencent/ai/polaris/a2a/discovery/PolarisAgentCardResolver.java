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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * AgentScope A2A card resolver backed by polaris-java.
 *
 * <p>Service mapping matches {@link com.tencent.ai.polaris.a2a.registry.PolarisAgentRegistry}:
 * the agent name is the Polaris service name. Discovery is pull-based —
 * {@link #getAgentCard(String)} calls {@code ConsumerAPI.getAllInstances} and rebuilds
 * the card from instance metadata {@link PolarisA2aConstants#META_AGENT_CARD} (full
 * AgentCard JSON written at register time).
 *
 * <p>Candidate instances are skipped when they are unhealthy, isolated, missing
 * {@code a2a.agent.card}, fail JSON decode, or carry a card whose {@code name} does
 * not match the requested agent. The first remaining instance wins.
 *
 * <p>Cache semantics for {@code refreshIntervalMs} (default
 * {@link PolarisA2aConstants#DEFAULT_REFRESH_INTERVAL_MS}):
 * <ul>
 *   <li>{@code >0} — TTL; refresh is done outside {@link ConcurrentHashMap} compute
 *       locks so a Polaris RPC does not pin a bucket</li>
 *   <li>{@code 0} — no cache; every call hits Polaris</li>
 *   <li>{@code <0} — cache forever until {@link #invalidate(String)} /
 *       {@link #invalidateAll()}</li>
 * </ul>
 * On TTL refresh failure the previous cache entry is kept (warn log). A true miss
 * throws {@link AgentCardNotFoundException}; Polaris SDK / network errors throw
 * {@link IllegalStateException} so callers can retry.
 */
public class PolarisAgentCardResolver implements AgentCardResolver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisAgentCardResolver.class);

    private final ConsumerAPI consumerAPI;
    private final String namespace;
    private final long refreshIntervalMs;
    private final LongSupplier currentTimeMillis;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Builds a resolver sharing the given Polaris context, with the default discovery TTL.
     *
     * @param context shared SDK context (namespace + {@link ConsumerAPI})
     */
    public PolarisAgentCardResolver(PolarisContextManager context) {
        this(context.consumerAPI(), context.getNamespace(),
                PolarisA2aConstants.DEFAULT_REFRESH_INTERVAL_MS, System::currentTimeMillis);
    }

    PolarisAgentCardResolver(ConsumerAPI consumerAPI, String namespace) {
        this(consumerAPI, namespace, PolarisA2aConstants.DEFAULT_REFRESH_INTERVAL_MS, System::currentTimeMillis);
    }

    /**
     * Package-visible constructor for tests that inject {@link ConsumerAPI} and a clock.
     *
     * @param refreshIntervalMs see class javadoc for {@code >0} / {@code 0} / {@code <0}
     * @param currentTimeMillis injectable clock used for TTL decisions
     */
    PolarisAgentCardResolver(
            ConsumerAPI consumerAPI,
            String namespace,
            long refreshIntervalMs,
            LongSupplier currentTimeMillis) {
        this.consumerAPI = Objects.requireNonNull(consumerAPI, "consumerAPI");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.refreshIntervalMs = refreshIntervalMs;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    /**
     * Resolve the AgentCard for {@code agentName} according to the cache policy.
     *
     * @param agentName Polaris service name / {@link AgentCard#name()}
     * @return the first usable card from healthy, non-isolated instances
     * @throws AgentCardNotFoundException if no instance has a usable card
     * @throws IllegalStateException if the Polaris lookup itself fails
     */
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

    /** Cache forever: Polaris fetch only on miss; concurrent misses may both fetch. */
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

    /**
     * TTL cache: serve a fresh entry; on expiry fetch outside the map lock and keep
     * the previous card if the refresh throws.
     */
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

    /**
     * Pull all instances of {@code agentName} and decode {@code a2a.agent.card}
     * from the first healthy, non-isolated candidate.
     */
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
            String json = metadata.get(PolarisA2aConstants.META_AGENT_CARD);
            if (json == null || json.isBlank()) {
                continue;
            }
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

    /** Releases the in-memory card cache; does not close the shared {@link ConsumerAPI}. */
    @Override
    public void close() {
        cache.clear();
    }

    public static Builder builder(PolarisContextManager context) {
        return new Builder(context);
    }

    /**
     * Fluent factory. {@link #refreshIntervalMs(long)} defaults to
     * {@link PolarisA2aConstants#DEFAULT_REFRESH_INTERVAL_MS}.
     */
    public static final class Builder {

        private final PolarisContextManager context;
        private long refreshIntervalMs = PolarisA2aConstants.DEFAULT_REFRESH_INTERVAL_MS;

        private Builder(PolarisContextManager context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        /**
         * {@code >0} TTL millis, {@code 0} no cache, {@code <0} cache until invalidate.
         */
        public Builder refreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
            return this;
        }

        public PolarisAgentCardResolver build() {
            return new PolarisAgentCardResolver(
                    context.consumerAPI(),
                    context.getNamespace(),
                    refreshIntervalMs,
                    System::currentTimeMillis);
        }
    }

    /** Cached card plus wall-clock millis when it was stored. */
    private record CacheEntry(AgentCard card, long fetchedAtMs) {
    }
}
