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

package com.tencent.ai.polaris.example.a2a.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillCommandMockModelTest {

    private static final String SKILL_ID = "id\"\\x";

    private final SkillCommandMockModel model = new SkillCommandMockModel();

    @Test
    void modelNameIsStable() {
        assertThat(model.getModelName()).isEqualTo("skill-command-mock");
    }

    @Test
    void unknownInputReturnsUsage() {
        ChatResponse response = respond(user("hello"));

        assertThat(response.getFinishReason()).isEqualTo("stop");
        assertThat(text(response)).contains("/skill-list").contains("/skill <name>");
        assertThat(response.getUsage().getInputTokens()).isZero();
    }

    @Test
    void nullOrEmptyHistoryReturnsUsage() {
        assertThat(text(model.stream(null, null, null).blockFirst())).contains("/skill-list");
        assertThat(text(respond())).contains("/skill <name>");
    }

    @Test
    void skillListRendersInjectedCatalog() {
        String catalog = """
                <available_skills>
                  <skill>
                    <name>Weather &amp; Maps</name>
                    <description>Check &lt;weather&gt; &amp; &quot;maps&quot; &apos;now&apos;</description>
                    <skill-id>%s</skill-id>
                  </skill>
                  <skill>
                    <skill-id>only-id</skill-id>
                    <description></description>
                  </skill>
                  <skill>
                    <name>skipped</name>
                  </skill>
                </available_skills>
                """.formatted(SKILL_ID);

        String rendered = text(respond(
                system(catalog),
                systemWithNullText(),
                null,
                user("  /SKILL-LIST  ")));

        assertThat(rendered)
                .startsWith("Skills pulled from Polaris (2):")
                .contains("- Weather & Maps — Check <weather> & \"maps\" 'now' [skill-id=" + SKILL_ID + "]")
                .contains("- only-id [skill-id=only-id]")
                .contains("Run '/skill <name>'")
                .doesNotContain("skipped");
    }

    @Test
    void skillListWithoutCatalogExplainsTheGap() {
        String rendered = text(respond(system("no catalog here"), user("/skill-list")));

        assertThat(rendered).contains("No skill was injected into the system prompt");
    }

    @Test
    void loadCommandEmitsToolCallForNameOrId() {
        Msg catalog = system(oneSkill("Weather", "Forecast", SKILL_ID));

        ChatResponse byName = respond(catalog, user("/Skill weather"));
        ChatResponse byId = respond(catalog, user("/SKILL " + SKILL_ID.toUpperCase()));

        assertToolCall(byName);
        assertToolCall(byId);
    }

    @Test
    void loadCommandRejectsBlankOrUnknownNames() {
        Msg catalog = system(oneSkill("Weather", "", "weather-id"));

        assertThat(text(respond(catalog, user("/skill "))))
                .contains("Supported commands");
        assertThat(text(respond(catalog, user("/skill missing"))))
                .contains("No skill named 'missing'")
                .contains("- Weather [skill-id=weather-id]");
        assertThat(text(respond(user("/skillfoo")))).contains("Supported commands");
    }

    @Test
    void toolResultBecomesTheNextAnswer() {
        ToolResultBlock result = new ToolResultBlock(
                "mock-1",
                "load_skill_through_path",
                List.of(
                        TextBlock.builder().text("line-a").build(),
                        ThinkingBlock.builder().thinking("ignored").build(),
                        TextBlock.builder().text("line-b").build()));
        Msg tool = Msg.builder().role(MsgRole.TOOL).content(result).build();

        ChatResponse response = respond(user("/skill weather"), assistant("calling"), tool);

        assertThat(response.getFinishReason()).isEqualTo("stop");
        assertThat(text(response)).isEqualTo("line-aline-b");
    }

    @Test
    void laterUserMessageIgnoresEarlierToolOutput() {
        ToolResultBlock result = ToolResultBlock.text("stale");
        Msg tool = Msg.builder().role(MsgRole.TOOL).content(result).build();

        assertThat(text(respond(tool, null, user("/skill-list"))))
                .contains("No skill was injected");
    }

    @Test
    void emptyToolOutputIsStillReturned() {
        ToolResultBlock result = new ToolResultBlock(
                "mock-1", "load_skill_through_path", List.<ContentBlock>of());
        Msg tool = Msg.builder().role(MsgRole.TOOL).content(result).build();

        assertThat(text(respond(tool))).isEmpty();
    }

    private static void assertToolCall(ChatResponse response) {
        assertThat(response.getFinishReason()).isEqualTo("tool_calls");
        assertThat(response.getContent()).hasSize(1);
        ToolUseBlock toolUse = (ToolUseBlock) response.getContent().get(0);
        assertThat(toolUse.getName()).isEqualTo("load_skill_through_path");
        assertThat(toolUse.getId()).isEqualTo("mock-" + SKILL_ID);
        assertThat(toolUse.getInput()).containsEntry("skillId", SKILL_ID).containsEntry("path", "SKILL.md");
        assertThat(toolUse.getContent()).isEqualTo(
                "{\"skillId\":\"" + "id\\\"\\\\x" + "\",\"path\":\"SKILL.md\"}");
    }

    private ChatResponse respond(Msg... messages) {
        return model.stream(Arrays.asList(messages), List.of(), null).blockFirst();
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg system(String text) {
        return Msg.builder().role(MsgRole.SYSTEM).textContent(text).build();
    }

    private static Msg assistant(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static Msg systemWithNullText() {
        Msg msg = mock(Msg.class);
        when(msg.getRole()).thenReturn(MsgRole.SYSTEM);
        when(msg.getTextContent()).thenReturn(null);
        return msg;
    }

    private static String oneSkill(String name, String description, String skillId) {
        return "<available_skills><skill><name>" + name + "</name><description>"
                + description + "</description><skill-id>" + skillId + "</skill-id></skill></available_skills>";
    }

    private static String text(ChatResponse response) {
        return ((TextBlock) response.getContent().get(0)).getText();
    }
}
