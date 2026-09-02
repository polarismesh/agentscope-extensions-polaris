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
import com.tencent.ai.polaris.skill.PolarisSkillRepository;
import io.agentscope.core.skill.AgentSkill;
import java.util.List;

/**
 * Lists published skills from Polaris SkillAPI, then loads one zip.
 *
 * <p>Environment / system properties:
 * <ul>
 *   <li>{@code POLARIS_ADDRESS} — discovery/registry, default {@code 127.0.0.1:8091}
 *   <li>{@code POLARIS_SKILL_ADDRESS} — SkillAPI, default {@code 127.0.0.1:8094}
 *   <li>{@code POLARIS_SKILL_NAME} / {@code -Dskill.name} — skill to load; empty loads the first listed name
 * </ul>
 */
public final class PolarisSkillClientExample {

    public static void main(String[] args) throws Exception {
        String address = firstNonBlank(System.getenv("POLARIS_ADDRESS"), "127.0.0.1:8091");
        String skillAddress = firstNonBlank(System.getenv("POLARIS_SKILL_ADDRESS"), "127.0.0.1:8094");
        String skillName = firstNonBlank(
                System.getenv("POLARIS_SKILL_NAME"),
                System.getProperty("skill.name"));

        try (PolarisContextManager context = PolarisContextManager.fromAddress(address, skillAddress)) {
            PolarisSkillRepository repo = new PolarisSkillRepository(context);
            System.out.println("listing skills");
            List<String> names = repo.getAllSkillNames();
            System.out.println("discovery=" + address + " skillApi=" + skillAddress);
            System.out.println("skills: " + names);
            if (names.isEmpty()) {
                return;
            }
            String toLoad = skillName.isEmpty() ? names.get(0) : skillName;
            AgentSkill skill = repo.getSkill(toLoad);
            System.out.println(
                    "loaded " + skill.getName()
                            + " source=" + skill.getSource()
                            + " resources=" + skill.getResourcePaths());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private PolarisSkillClientExample() {}
}
