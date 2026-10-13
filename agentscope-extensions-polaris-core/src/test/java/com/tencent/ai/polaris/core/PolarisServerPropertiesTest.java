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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolarisServerPropertiesTest {

    @Test
    void skillAddressListDerivesPort8094FromDiscoveryHost() {
        PolarisServerProperties props = new PolarisServerProperties("192.168.1.8:8091");
        assertEquals(List.of("192.168.1.8:8094"), props.skillAddressList());
    }

    @Test
    void skillAddressListUsesExplicitOverride() {
        PolarisServerProperties props = new PolarisServerProperties("127.0.0.1:8091");
        props.setSkillAddress("10.0.0.2:8094,10.0.0.3:8094");
        assertEquals(List.of("10.0.0.2:8094", "10.0.0.3:8094"), props.skillAddressList());
    }

    @Test
    void skillAddressListMapsEachDiscoveryHost() {
        PolarisServerProperties props = new PolarisServerProperties("a:8091, b:8091");
        assertEquals(List.of("a:8094", "b:8094"), props.skillAddressList());
    }

    @Test
    void defaultDiscoveryFallsBackToLocalhostAndSkillPort() {
        PolarisServerProperties props = new PolarisServerProperties();
        assertEquals(List.of(PolarisServerProperties.DEFAULT_ADDRESS), props.serverAddressList());
        assertEquals(List.of(PolarisServerProperties.DEFAULT_SKILL_ADDRESS), props.skillAddressList());
        assertTrue(props.isEnabled());
        assertNull(props.getAddress());
        assertNull(props.getSkillAddress());
        assertEquals(PolarisServerProperties.DEFAULT_NAMESPACE, props.getNamespace());
        assertNull(props.getToken());
    }

    @Test
    void addressListsDropBlankSegments() {
        PolarisServerProperties props = new PolarisServerProperties();
        props.setAddress(" , a:8091, ,b:8091, ");
        props.setSkillAddress(" , 10.0.0.2:8094, ,10.0.0.3:8094, ");
        props.setEnabled(false);
        props.setNamespace(" prod ");
        props.setToken("secret");

        assertEquals(List.of("a:8091", "b:8091"), props.serverAddressList());
        assertEquals(List.of("10.0.0.2:8094", "10.0.0.3:8094"), props.skillAddressList());
        assertEquals(" , a:8091, ,b:8091, ", props.getAddress());
        assertEquals(" , 10.0.0.2:8094, ,10.0.0.3:8094, ", props.getSkillAddress());
        assertFalse(props.isEnabled());
        assertEquals(" prod ", props.getNamespace());
        assertEquals("secret", props.getToken());
    }

    @Test
    void blankSkillAddressFallsBackToDiscoveryHosts() {
        PolarisServerProperties props = new PolarisServerProperties("192.168.1.8:8091");
        props.setSkillAddress("   ");
        assertEquals(List.of("192.168.1.8:8094"), props.skillAddressList());
        props.setSkillAddress(null);
        assertEquals(List.of("192.168.1.8:8094"), props.skillAddressList());
    }

    @Test
    void withSkillPortRewritesHostForIpv6AndNonNumericPorts() {
        assertEquals("[2001:db8::1]:8094", PolarisServerProperties.withSkillPort("[2001:db8::1]:8091"));
        assertEquals("[::8094", PolarisServerProperties.withSkillPort("[::1"));
        assertEquals("localhost:8094", PolarisServerProperties.withSkillPort("localhost"));
        assertEquals(":8091:8094", PolarisServerProperties.withSkillPort(":8091"));
        assertEquals("host:abc:8094", PolarisServerProperties.withSkillPort("host:abc"));
        assertEquals("host::8094", PolarisServerProperties.withSkillPort("host:"));
    }
}
