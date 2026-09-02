package com.tencent.ai.polaris.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }
}
