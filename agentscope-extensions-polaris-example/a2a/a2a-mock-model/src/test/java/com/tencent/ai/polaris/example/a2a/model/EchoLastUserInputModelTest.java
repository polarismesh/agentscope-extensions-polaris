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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EchoLastUserInputModelTest {

    private final EchoLastUserInputModel model = new EchoLastUserInputModel();

    @Test
    void modelNameIsStable() {
        assertThat(model.getModelName()).isEqualTo("echo-last-user-input");
    }

    @Test
    void echoesTheLatestUserText() {
        Msg older = Msg.builder().role(MsgRole.USER).textContent("first").build();
        Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("ignored").build();
        Msg latest = Msg.builder().role(MsgRole.USER).textContent("second").build();

        ChatResponse response = model.stream(Arrays.asList(older, system, null, latest), List.of(), null).blockFirst();

        assertThat(response.getFinishReason()).isEqualTo("stop");
        assertThat(((TextBlock) response.getContent().get(0)).getText()).isEqualTo("second");
        assertThat(response.getUsage().getOutputTokens()).isZero();
    }

    @Test
    void missingUserTextEchoesEmptyString() {
        assertThat(text(model.stream(null, null, null).blockFirst())).isEmpty();
        assertThat(text(model.stream(List.of(), null, null).blockFirst())).isEmpty();
        assertThat(text(model.stream(
                List.of(Msg.builder().role(MsgRole.SYSTEM).textContent("only system").build()),
                null,
                null).blockFirst())).isEmpty();

        Msg blank = mock(Msg.class);
        when(blank.getRole()).thenReturn(MsgRole.USER);
        when(blank.getTextContent()).thenReturn(null);
        assertThat(text(model.stream(Arrays.asList(null, blank), null, null).blockFirst())).isEmpty();
    }

    private static String text(ChatResponse response) {
        return ((TextBlock) response.getContent().get(0)).getText();
    }
}
