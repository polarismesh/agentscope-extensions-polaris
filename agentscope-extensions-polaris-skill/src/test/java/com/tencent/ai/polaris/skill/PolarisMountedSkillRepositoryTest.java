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

package com.tencent.ai.polaris.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadRequest;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.api.pojo.ServiceInfo;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.pojo.Services;
import com.tencent.polaris.api.rpc.GetServicesRequest;
import com.tencent.polaris.api.rpc.ServicesResponse;
import com.tencent.polaris.specification.api.v1.service.manage.ServiceProto;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PolarisMountedSkillRepositoryTest {

    @Mock private SkillAPI skillAPI;
    @Mock private ConsumerAPI consumerAPI;

    private PolarisMountedSkillRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PolarisMountedSkillRepository(
                skillAPI, consumerAPI, "default", "demo-agent", "");
    }

    @Test
    void constructorRejectsNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> new PolarisMountedSkillRepository(null, "demo-agent"));
    }

    @Test
    void constructorFromContextUsesSharedApis() {
        PolarisContextManager context = mock(PolarisContextManager.class);
        when(context.skillAPI()).thenReturn(skillAPI);
        when(context.consumerAPI()).thenReturn(consumerAPI);
        when(context.getNamespace()).thenReturn("default");

        PolarisMountedSkillRepository created =
                new PolarisMountedSkillRepository(context, "demo-agent");
        assertEquals("polaris-mounted:default/demo-agent", created.getSource());
    }

    @Test
    void constructorRejectsNullConsumerApi() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolarisMountedSkillRepository(
                        skillAPI, null, "default", "demo-agent", ""));
    }

    @Test
    void constructorRejectsBlankServiceName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolarisMountedSkillRepository(
                        skillAPI, consumerAPI, "default", "  ", ""));
    }

    @Test
    void sourceAndRepositoryInfoUseMountedIdentity() {
        assertEquals("polaris-mounted:default/demo-agent", repository.getSource());
        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
        assertEquals("polaris-mounted", info.getType());
        assertEquals("namespace:default/demo-agent", info.getLocation());
        assertFalse(info.isWritable());
        assertFalse(repository.isWriteable());
    }

    @Test
    void getAllSkillNamesReturnsMountedNamesWithoutDownloading() {
        when(consumerAPI.getServices(any())).thenReturn(servicesResponse(serviceWithSkills("sql-analysis")));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
        verify(skillAPI, never()).downloadSkill(any());
        verify(skillAPI, never()).listSkills(any());

        ArgumentCaptor<GetServicesRequest> captor = ArgumentCaptor.forClass(GetServicesRequest.class);
        verify(consumerAPI).getServices(captor.capture());
        assertEquals("default", captor.getValue().getNamespace());
        assertEquals("demo-agent", captor.getValue().getService());
    }

    @Test
    void getAllSkillNamesFallsBackToSkillIdWhenNameBlank() {
        ServiceProto.ExtendedMetadata meta = ServiceProto.ExtendedMetadata.newBuilder()
                .setType(ServiceProto.ExtendedMetadata.ExtendedMetadataType.EXTENDED_METADATA_SKILL)
                .setAgentSkill(ServiceProto.AgentSkill.newBuilder().setId("sql-analysis").build())
                .build();
        when(consumerAPI.getServices(any())).thenReturn(servicesResponse(serviceWithMetadata(meta)));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesDeduplicatesSkillNames() {
        when(consumerAPI.getServices(any()))
                .thenReturn(servicesResponse(serviceWithSkills("sql-analysis", "sql-analysis")));

        assertEquals(List.of("sql-analysis"), repository.getAllSkillNames());
    }

    @Test
    void getAllSkillNamesEmptyWhenServiceMissing() {
        when(consumerAPI.getServices(any())).thenReturn(servicesResponse());

        assertEquals(List.of(), repository.getAllSkillNames());
        verify(skillAPI, never()).downloadSkill(any());
    }

    @Test
    void getSkillRejectsUnmountedName() {
        when(consumerAPI.getServices(any())).thenReturn(servicesResponse(serviceWithSkills("sql-analysis")));

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> repository.getSkill("other-skill"));
        assertEquals("Skill not found: other-skill", e.getMessage());
        verify(skillAPI, never()).downloadSkill(any());
    }

    @Test
    void getSkillDownloadsMountedSkill() throws Exception {
        when(consumerAPI.getServices(any())).thenReturn(servicesResponse(serviceWithSkills("sql-analysis")));
        when(skillAPI.downloadSkill(any())).thenReturn(successZip("sql-analysis", "Analyze SQL", "Run EXPLAIN"));

        AgentSkill skill = repository.getSkill("sql-analysis");

        assertEquals("sql-analysis", skill.getName());
        assertEquals("Analyze SQL", skill.getDescription());
        assertEquals("Run EXPLAIN", skill.getSkillContent());
        assertEquals("polaris-mounted:default/demo-agent", skill.getSource());

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("sql-analysis", captor.getValue().getName());
        assertEquals("default", captor.getValue().getNamespace());
        assertEquals("zip", captor.getValue().getFormat());
    }

    @Test
    void getSkillSendsConfiguredVersion() throws Exception {
        repository = new PolarisMountedSkillRepository(
                skillAPI, consumerAPI, "default", "demo-agent", "1.0.0");
        when(consumerAPI.getServices(any())).thenReturn(servicesResponse(serviceWithSkills("sql-analysis")));
        when(skillAPI.downloadSkill(any())).thenReturn(successZip("sql-analysis", "Analyze SQL", "Run EXPLAIN"));

        repository.getSkill("sql-analysis");

        ArgumentCaptor<SkillDownloadRequest> captor = ArgumentCaptor.forClass(SkillDownloadRequest.class);
        verify(skillAPI).downloadSkill(captor.capture());
        assertEquals("1.0.0", captor.getValue().getVersion());
    }

    @Test
    void getAllSkillsReturnsMountedSkillsOnly() throws Exception {
        when(consumerAPI.getServices(any()))
                .thenReturn(servicesResponse(serviceWithSkills("sql-analysis", "chart-rendering")));
        when(skillAPI.downloadSkill(any())).thenAnswer(inv -> {
            SkillDownloadRequest req = inv.getArgument(0);
            return successZip(req.getName(), req.getName() + " desc", "body");
        });

        List<AgentSkill> skills = repository.getAllSkills();

        assertEquals(2, skills.size());
        assertEquals("sql-analysis", skills.get(0).getName());
        assertEquals("chart-rendering", skills.get(1).getName());
        verify(skillAPI, never()).listSkills(any());
    }

    @Test
    void skillExistsOnlyForMountedNames() {
        when(consumerAPI.getServices(any())).thenReturn(servicesResponse(serviceWithSkills("sql-analysis")));

        assertTrue(repository.skillExists("sql-analysis"));
        assertFalse(repository.skillExists("other-skill"));
        verify(skillAPI, never()).downloadSkill(any());
    }

    @Test
    void writesRemainNoOps() {
        AgentSkill skill = new AgentSkill("local-skill", "Local desc", "Local body", java.util.Map.of());
        assertFalse(repository.save(List.of(skill), true));
        assertFalse(repository.delete("local-skill"));
        repository.setWriteable(true);
        assertFalse(repository.isWriteable());
    }

    @Test
    void closeDoesNotDestroySharedApis() {
        repository.close();
        verify(skillAPI, never()).destroy();
        verify(skillAPI, never()).close();
        verify(consumerAPI, never()).close();
        verify(consumerAPI, never()).destroy();
    }

    private static ServicesResponse servicesResponse(ServiceInfo... infos) {
        List<ServiceInfo> list = List.of(infos);
        return new ServicesResponse(new Services() {
            @Override
            public ServiceKey getServiceKey() {
                return null;
            }

            @Override
            public List<ServiceInfo> getServices() {
                return list;
            }

            @Override
            public boolean isInitialized() {
                return true;
            }

            @Override
            public String getRevision() {
                return "";
            }
        });
    }

    private static ServiceInfo serviceWithSkills(String... names) {
        ServiceProto.ExtendedMetadata[] metas = new ServiceProto.ExtendedMetadata[names.length];
        for (int i = 0; i < names.length; i++) {
            metas[i] = ServiceProto.ExtendedMetadata.newBuilder()
                    .setType(ServiceProto.ExtendedMetadata.ExtendedMetadataType.EXTENDED_METADATA_SKILL)
                    .setAgentSkill(ServiceProto.AgentSkill.newBuilder().setName(names[i]).build())
                    .build();
        }
        return serviceWithMetadata(metas);
    }

    private static ServiceInfo serviceWithMetadata(ServiceProto.ExtendedMetadata... metas) {
        ServiceInfo info = new ServiceInfo();
        info.setNamespace("default");
        info.setService("demo-agent");
        info.setExtendedMetadata(List.of(metas));
        return info;
    }

    private static SkillDownloadResponse successZip(String name, String description, String body)
            throws IOException {
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(skillZip(name, description, body));
        return resp;
    }

    private static byte[] skillZip(String name, String description, String body) throws IOException {
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            zout.write(md.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        return bos.toByteArray();
    }
}
