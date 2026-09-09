# AgentScope Extensions Polaris

[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

English | [简体中文](./README-zh.md)

README:

- [Introduction](#introduction)
- [Modules](#modules)
- [Skill Usage](#skill-usage)
- [How to Build](#how-to-build)

## Introduction

AgentScope Extensions Polaris is an extension for [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) that integrates with [Polaris](https://polarismesh.cn/) (PolarisMesh) for MCP / A2A / Skill registration, discovery and governance.

- [Polaris Github](https://github.com/polarismesh/polaris)
- [AgentScope Java Github](https://github.com/agentscope-ai/agentscope-java)

AgentScope with Polaris can solve these problems:

- **MCP Management** — Register MCP Server instances to Polaris on startup; discover and load-balance MCP Servers from the client side
- **A2A Management** — Register Agent-to-Agent (A2A) endpoints to Polaris and discover peer agents dynamically
- **Skill Management** — Store and version AgentScope Skills in Polaris for centralized distribution

## Modules

```
agentscope-extensions-polaris (root)
├── agentscope-extensions-polaris-bom      # Bill of Materials
├── agentscope-extensions-polaris-core     # Polaris SDK wrapper (polaris-all)
├── agentscope-extensions-polaris-mcp      # MCP extension
├── agentscope-extensions-polaris-a2a      # A2A extension
└── agentscope-extensions-polaris-skill    # Skill extension
```

## Skill Usage

`PolarisSkillRepository` implements AgentScope's `AgentSkillRepository` interface and fetches published Skill packages from Polaris (read-only). See the full example at [`agentscope-extensions-polaris-example/skill/skill-client`](agentscope-extensions-polaris-example/skill/skill-client):

```bash
POLARIS_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
  java -jar agentscope-extensions-polaris-example/skill/skill-client/target/*-jar-with-dependencies.jar
```

To read only the skills declared on a Polaris service (`Service.extended_metadata`), use `PolarisMountedSkillRepository`. It extends `PolarisSkillRepository` and filters reads to the mounted name set. See [`agentscope-extensions-polaris-example/skill/skill-mounted-client`](agentscope-extensions-polaris-example/skill/skill-mounted-client):

```java
try (PolarisContextManager context =
        PolarisContextManager.fromAddress("127.0.0.1:8091", "127.0.0.1:8094")) {
    PolarisMountedSkillRepository repo =
            PolarisMountedSkillRepository.from(context, "demo-agent");
    List<AgentSkill> skills = repo.getAllSkills();
}
```

`serviceName` must match the A2A registration name. Run the example:

```bash
POLARIS_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
POLARIS_SERVICE=demo-agent \
  java -jar agentscope-extensions-polaris-example/skill/skill-mounted-client/target/*-jar-with-dependencies.jar
```

### Running an Agent on Polaris Skills

[`agentscope-extensions-polaris-example/skill/skill-agent`](agentscope-extensions-polaris-example/skill/skill-agent)
runs a `ReActAgent` on top of either repository. `ReActAgent.Builder` takes a `SkillBox` rather
than a repository, so the example bridges the two and lets the built-in `SkillHook` inject the
catalog on every turn:

```java
Toolkit toolkit = new Toolkit();
SkillBox skillBox = new SkillBox(toolkit);
for (AgentSkill skill : repo.getAllSkills()) {
    skillBox.registration().skill(skill).apply();
}
ReActAgent agent = ReActAgent.builder()
        .model(model)
        .toolkit(toolkit)
        .skillBox(skillBox)      // registers load_skill_through_path + SkillHook
        .memory(new InMemoryMemory())
        .build();
```

The chat loop understands `/skill-list` (list the skills that reached the agent) and
`/skill <name>` (print one skill's SKILL.md through `load_skill_through_path`). Both commands are
documented in the system prompt, so an OpenAI-compatible model answers them itself:

```bash
POLARIS_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
POLARIS_SKILL_SOURCE=published \
TOKEN_HUB_API_KEY=sk-xxx TOKEN_HUB_BASE_URL=https://api.openai.com/v1 OPENAI_MODEL=gpt-4o \
  java -jar agentscope-extensions-polaris-example/skill/skill-agent/target/*-jar-with-dependencies.jar
```

Set `POLARIS_SKILL_SOURCE=mounted` plus `POLARIS_SERVICE=demo-agent` to read the mounted skills of
one service instead. When no LLM is reachable, drop `TOKEN_HUB_API_KEY` (or set
`SKILL_AGENT_MOCK_LLM=true`) and an offline mock model answers the same two commands — it reads the
catalog straight out of the injected system prompt and issues the real `load_skill_through_path`
call, so the Polaris → SkillBox → agent path is still exercised end to end.

Note that the skill catalog is a startup snapshot; picking up skill changes on every turn needs
`HarnessAgent` and its `DynamicSkillHook`:

```java
try (PolarisContextManager context =
        PolarisContextManager.fromAddress("127.0.0.1:8091", "127.0.0.1:8094")) {
    AgentSkillRepository repo = PolarisSkillRepository.from(context);
    agent = HarnessAgent.builder().skillRepository(repo) /* ... */ .build();
}
```

### Spring Boot

Add the `agentscope-extensions-polaris-spring-boot-starter` dependency and configure:

```yaml
agentscope:
  polaris:
    address: 127.0.0.1:8091          # registry / discovery
    skill-address: 127.0.0.1:8094    # SkillAPI; default is discovery host + 8094
    namespace: default
    skill:
      enabled: true
      names: []          # empty lists all published skills
      version: ""        # empty uses activeVersion
```

Inject the auto-configured `AgentSkillRepository` bean into `HarnessAgent` / `ReActAgent` (same as existing SkillBox registration).

### Notes

- **Read-only**: `save` / `delete` / `watch` are not supported; Skills are published and managed in the Polaris console.
- **Local cache**: Downloaded Skill zip files are cached by polaris-java under `./polaris/backup/skill`.
- **Shared connection**: `SkillAPI` shares the same `SDKContext` as the A2A extension. `PolarisSkillRepository.close()` is a no-op and must **not** destroy `SkillAPI` or the `SDKContext`. `PolarisContextManager.close()` / `SkillAPI.destroy()` **do** destroy the shared `SDKContext` and must outlive the Agent. The try-with-resources snippet above is fine for a short example; production Agents must keep the context for the Agent lifetime.

## How to Build

**Linux and Mac**

```bash
./mvnw clean install
```

**Windows**

```bash
.\mvnw.cmd clean install
```

## License

This project is licensed under the [BSD 3-Clause License](https://opensource.org/licenses/BSD-3-Clause).
