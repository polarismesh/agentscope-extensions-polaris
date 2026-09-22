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

import com.tencent.polaris.api.config.Configuration;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.core.ProviderAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.client.api.SDKContext;
import com.tencent.polaris.factory.ConfigAPIFactory;
import com.tencent.polaris.factory.api.DiscoveryAPIFactory;

import com.tencent.polaris.factory.config.ConfigurationImpl;
import com.tencent.polaris.factory.config.global.GlobalConfigImpl;
import com.tencent.polaris.factory.config.global.ServerConnectorConfigImpl;
import com.tencent.polaris.factory.config.provider.LosslessConfigImpl;
import java.util.List;
import java.util.Objects;

/**
 * Lazily builds and holds a shared {@link SDKContext} plus the {@link ProviderAPI} and
 * {@link ConsumerAPI} derived from it. Intended to be constructed once and passed
 * (via builders) to the A2A registry/resolver — and later skill/MCP features — so they
 * all share one connection and one set of plugins.
 *
 * <p>Not a static singleton: callers build it explicitly (e.g.
 * {@code PolarisContextManager.fromAddress(addr)}) and pass it down. In a Spring Boot
 * starter it would be a {@code @Bean}.
 */
public class PolarisContextManager implements AutoCloseable {

    private final PolarisServerProperties properties;
    private final SDKContext sdkContext;
    private final ProviderAPI providerAPI;
    private final ConsumerAPI consumerAPI;

    /**
     * Build a context from the given server address (e.g. {@code "127.0.0.1:8091"}).
     */
    public static PolarisContextManager fromAddress(String address) {
        return fromAddress(List.of(address));
    }

    public static PolarisContextManager fromAddress(List<String> addresses) {
        PolarisServerProperties props = new PolarisServerProperties(String.join(",", addresses));
        return new PolarisContextManager(props);
    }

    public PolarisContextManager(PolarisServerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        ConfigurationImpl config = buildConfiguration(properties);

        this.sdkContext = initContext(config);
        this.providerAPI = createProviderAPI(this.sdkContext);
        this.consumerAPI = createConsumerAPI(this.sdkContext);
    }

    static ConfigurationImpl buildConfiguration(PolarisServerProperties properties) {
        Objects.requireNonNull(properties, "properties");
        List<String> addresses = properties.serverAddressList();
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("polaris server address must be configured");
        }
        ConfigurationImpl config = (ConfigurationImpl) ConfigAPIFactory.createConfigurationByAddress(addresses);
        GlobalConfigImpl globalConfig = (GlobalConfigImpl) config.getGlobal();
        String token = properties.getToken();
        if (token != null && !token.isBlank()) {
            ((ServerConnectorConfigImpl) globalConfig.getServerConnector()).setToken(token);
        }
        globalConfig.getStatReporter().setEnable(false);
        LosslessConfigImpl losslessConfig = (LosslessConfigImpl) config.getProvider().getLossless();
        losslessConfig.setEnable(false);
        return config;
    }

    private static SDKContext initContext(Configuration config) {
        try {
            return SDKContext.initContextByConfig(config);
        } catch (PolarisException e) {
            throw new IllegalStateException("Failed to init polaris SDKContext: " + e.getMessage(), e);
        }
    }

    private static ProviderAPI createProviderAPI(SDKContext context) {
        try {
            return DiscoveryAPIFactory.createProviderAPIByContext(context);
        } catch (PolarisException e) {
            throw new IllegalStateException("Failed to create polaris ProviderAPI: " + e.getMessage(), e);
        }
    }

    private static ConsumerAPI createConsumerAPI(SDKContext context) {
        try {
            return DiscoveryAPIFactory.createConsumerAPIByContext(context);
        } catch (PolarisException e) {
            throw new IllegalStateException("Failed to create polaris ConsumerAPI: " + e.getMessage(), e);
        }
    }

    public PolarisServerProperties getProperties() {
        return properties;
    }

    public SDKContext getSdkContext() {
        return sdkContext;
    }

    public ProviderAPI providerAPI() {
        return providerAPI;
    }

    public ConsumerAPI consumerAPI() {
        return consumerAPI;
    }

    public String getNamespace() {
        return properties.getNamespace();
    }

    @Override
    public void close() {
        if (providerAPI != null) {
            providerAPI.close();
        }
        if (consumerAPI != null) {
            consumerAPI.close();
        }
        if (sdkContext != null) {
            sdkContext.close();
        }
    }
}
