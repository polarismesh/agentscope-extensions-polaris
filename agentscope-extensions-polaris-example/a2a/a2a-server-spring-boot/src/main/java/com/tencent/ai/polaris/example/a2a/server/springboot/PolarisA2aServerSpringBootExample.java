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

package com.tencent.ai.polaris.example.a2a.server.springboot;

import com.tencent.ai.polaris.example.a2a.server.springboot.tools.ExampleTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot A2A server example backed by Polaris.
 *
 * <p>Almost zero wiring: the {@code agentscope-spring-boot-starter} builds the {@link io.agentscope.core.ReActAgent}
 * from {@code agentscope.*} config; the {@code agentscope-a2a-spring-boot-starter} assembles
 * {@link io.agentscope.core.a2a.server.AgentScopeA2aServer} + the JSON-RPC controller and collects every
 * {@link io.agentscope.core.a2a.server.registry.AgentRegistry} bean (including the {@code PolarisAgentRegistry}
 * contributed by our own {@code agentscope-extensions-polaris-spring-boot-starter}). On {@code ApplicationReadyEvent}
 * the server registers the agent card + transport to Polaris. Advertise host is not configured
 * ({@code server.address} is omitted); AgentScope fills the local IP.
 *
 * <p>Configure via {@code src/main/resources/application.yml} (or env vars), then run:
 * <pre>{@code
 * # fetch the agent card
 * curl http://localhost:8888/.well-known/agent-card.json
 * # send a streaming message
 * curl -N -X POST http://localhost:8888/ -H 'Content-Type: application/json' \
 *   -d '{"jsonrpc":"2.0","id":"1","method":"message/stream","params":{"message":{"role":"user","kind":"message","parts":[{"kind":"text","text":"What is 346 * 47?"}],"messageId":"m1","contextId":"c1"}}}'
 * }</pre>
 * Test environments often cannot reach an LLM. Echo the last user message by default
 * ({@code A2A_ECHO_USER_INPUT=true}). Set it to {@code false} and provide
 * {@code TOKEN_HUB_API_KEY} to use an OpenAI-compatible model.
 *
 * <p>Or talk to it with {@code PolarisA2aClientExample} / the Spring Boot client.
 */
@SpringBootApplication
public class PolarisA2aServerSpringBootExample {

    private static final Logger log = LoggerFactory.getLogger(PolarisA2aServerSpringBootExample.class);

    public static void main(String[] args) {
        SpringApplication.run(PolarisA2aServerSpringBootExample.class, args);
    }

    /**
     * Offline Model used when {@code a2a.echo-user-input} is true (default).
     * Presence of this bean skips the starter's OpenAI {@link Model} auto-config.
     */
    @Bean
    @ConditionalOnProperty(name = "a2a.echo-user-input", havingValue = "true", matchIfMissing = true)
    public Model echoLastUserInputModel() {
        EchoLastUserInputModel model = new EchoLastUserInputModel();
        log.info("Using model {}", model.getModelName());
        return model;
    }

    /**
     * Register the example tools so the auto-built ReActAgent can call them.
     *
     * <p>The agentscope-spring-boot-starter exposes Toolkit as a prototype-scoped bean with
     * {@code @ConditionalOnMissingBean}; supplying our own here adds the tools to that kit.
     */
    @Bean
    public Toolkit toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());
        return toolkit;
    }
}
