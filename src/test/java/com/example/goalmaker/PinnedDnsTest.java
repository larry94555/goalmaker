package com.example.goalmaker;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedDnsTest {
    @Test
    void resolvesOnceAndReturnsOnlyThePinnedAddresses() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress reboundAddress = InetAddress.getByName("127.0.0.1");
        PinnedDns dns = new PinnedDns(host -> resolutions.incrementAndGet() == 1
                ? List.of(publicAddress) : List.of(reboundAddress), false);

        assertEquals(List.of(publicAddress), dns.lookup("example.com"));
        assertEquals(List.of(publicAddress), dns.lookup("EXAMPLE.COM"));
        assertEquals(List.of(publicAddress), dns.pinnedAddresses("example.com"));
        assertEquals(1, resolutions.get());
    }

    @Test
    void rejectsPrivateDnsAnswersAndUnapprovedPorts() throws Exception {
        PinnedDns dns = new PinnedDns(host -> List.of(InetAddress.getByName("127.0.0.1")), false);
        UnknownHostException privateAddress = assertThrows(UnknownHostException.class,
                () -> dns.lookup("private.example"));
        assertTrue(privateAddress.getMessage().contains("private or local"));

        PinnedDns publicDns = new PinnedDns(host -> List.of(InetAddress.getByName("8.8.8.8")), false);
        FetchUrlPolicy policy = new FetchUrlPolicy(publicDns, Set.of(80, 443));
        IllegalArgumentException port = assertThrows(IllegalArgumentException.class,
                () -> policy.validate(URI.create("https://example.com:8443/page")));
        assertEquals("url port 8443 is not allowed", port.getMessage());
    }
}
