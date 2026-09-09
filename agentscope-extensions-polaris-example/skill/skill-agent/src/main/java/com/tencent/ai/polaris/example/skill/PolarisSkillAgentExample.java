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

package com.tencent.ai.polaris.example.skill;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.skill.PolarisMountedSkillRepository;
import com.tencent.ai.polaris.skill.PolarisSkillRepository;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs a {@link ReActAgent} whose skills are pulled from Polaris.
 *
 * <p>Skills come from {@link PolarisSkillRepository} (every published skill in the namespace) or
 * {@link PolarisMountedSkillRepository} (only the skills mounted on one agent service), are loaded
 * into a {@link SkillBox}, and reach the model through the {@code SkillHook} that
 * {@link ReActAgent.Builder#skillBox(SkillBox)} installs. The skill catalog is a startup snapshot;
 * refreshing it on every turn needs {@code HarnessAgent} and its {@code DynamicSkillHook}.
 *
 * <p>Two commands are documented in the system prompt and work in both model modes:
 * <ul>
 *   <li>{@code /skill-list} — list the skills that reached the agent
 *   <li>{@code /skill <name>} — print one skill's SKILL.md via {@code load_skill_through_path}
 * </ul>
 * {@code /exit} quits.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code POLARIS_ADDRESS} — discovery/registry, default {@code 127.0.0.1:8091}
 *   <li>{@code POLARIS_SKILL_ADDRESS} — SkillAPI, default {@code 127.0.0.1:8094}
 *   <li>{@code POLARIS_SKILL_SOURCE} — {@code published} (default) or {@code mounted}
 *   <li>{@code POLARIS_SERVICE} — agent service name for {@code mounted}, default {@code demo-agent}
 *   <li>{@code SKILL_AGENT_MOCK_LLM} — {@code true} forces the offline mock model; when unset the
 *       mock is used automatically if {@code TOKEN_HUB_API_KEY} is missing
 *   <li>{@code TOKEN_HUB_API_KEY} — OpenAI-compatible API key
 *   <li>{@code TOKEN_HUB_BASE_URL} — default {@code https://api.openai.com/v1}
 *   <li>{@code OPENAI_MODEL} — default {@code deepseek-v4-flash}
 * </ul>
 */
public final class PolarisSkillAgentExample {

    private static final String SYS_PROMPT = "You are an assistant whose skills are published in Polaris.\n"
            + "Command handling:\n"
            + "- When the user sends '/skill-list', list every available skill with its name and "
            + "description. Do not call any tool.\n"
            + "- When the user sends '/skill <name>', call load_skill_through_path(skillId=<the "
            + "skill-id of that skill>, path=\"SKILL.md\") and reply with the returned content "
            + "verbatim.\n"
            + "Otherwise answer normally, loading a skill first when one matches the request.";

    public static void main(String[] args) throws Exception {
        String address = firstNonBlank(System.getenv("POLARIS_ADDRESS"), "127.0.0.1:8091");
        String skillAddress = firstNonBlank(System.getenv("POLARIS_SKILL_ADDRESS"), "127.0.0.1:8094");
        String skillSource = firstNonBlank(System.getenv("POLARIS_SKILL_SOURCE"), "published");
        String serviceName = firstNonBlank(System.getenv("POLARIS_SERVICE"), "demo-agent");

        // PolarisContextManager owns the SDKContext shared by SkillAPI and ConsumerAPI; it must
        // outlive the agent, hence the try-with-resources wrapping the whole chat loop.
        try (PolarisContextManager context = PolarisContextManager.fromAddress(address, skillAddress);
                AgentSkillRepository repo = openRepository(context, skillSource, serviceName)) {
            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);
            List<AgentSkill> skills = repo.getAllSkills();
            for (AgentSkill skill : skills) {
                skillBox.registration().skill(skill).apply();
            }
            System.out.println("discovery=" + address + " skillApi=" + skillAddress
                    + " source=" + repo.getSource());
            System.out.println("skills loaded: " + repo.getAllSkillNames());

            boolean mock = useMockModel();
            Model model = mock ? new SkillCommandMockModel() : openAiModel();
            ReActAgent agent = ReActAgent.builder()
                    .name("polaris-skill-agent")
                    .description("A ReAct agent whose skills are pulled from Polaris.")
                    .sysPrompt(SYS_PROMPT)
                    .model(model)
                    .toolkit(toolkit)
                    // Registers load_skill_through_path and the SkillHook that injects the catalog.
                    .skillBox(skillBox)
                    .memory(new InMemoryMemory())
                    // The mock answers /skill <name> with one tool call plus one final turn.
                    .maxIters(mock ? 3 : 10)
                    .build();

            System.out.println("model=" + model.getModelName() + (mock ? " (offline mock)" : ""));
            System.out.println("commands: /skill-list, /skill <name>, /exit");
            chat(agent);
        }
    }

    private static AgentSkillRepository openRepository(
            PolarisContextManager context, String skillSource, String serviceName) {
        if ("mounted".equalsIgnoreCase(skillSource)) {
            return PolarisMountedSkillRepository.from(context, serviceName);
        }
        if (!"published".equalsIgnoreCase(skillSource)) {
            throw new IllegalArgumentException(
                    "POLARIS_SKILL_SOURCE must be 'published' or 'mounted': " + skillSource);
        }
        return PolarisSkillRepository.from(context);
    }

    private static void chat(ReActAgent agent) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("You> ");
            System.out.flush();
            String input = reader.readLine();
            if (input == null || "/exit".equalsIgnoreCase(input.trim())) {
                return;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg msg = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text(input.trim()).build())
                    .build();
            try {
                Msg reply = agent.call(msg).block();
                System.out.println("Agent> " + (reply == null ? "" : reply.getTextContent()));
            } catch (RuntimeException e) {
                System.out.println("Agent> call failed: " + e.getMessage());
            }
        }
    }

    private static boolean useMockModel() {
        String flag = firstNonBlank(System.getenv("SKILL_AGENT_MOCK_LLM"));
        if (!flag.isEmpty()) {
            return isTruthy(flag);
        }
        return firstNonBlank(System.getenv("TOKEN_HUB_API_KEY")).isEmpty();
    }

    private static Model openAiModel() {
        return OpenAIChatModel.builder()
                .apiKey(System.getenv("TOKEN_HUB_API_KEY"))
                .baseUrl(firstNonBlank(System.getenv("TOKEN_HUB_BASE_URL"), "https://api.openai.com/v1"))
                .modelName(firstNonBlank(System.getenv("OPENAI_MODEL"), "deepseek-v4-flash"))
                .build();
    }

    private static boolean isTruthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private PolarisSkillAgentExample() {}
}
