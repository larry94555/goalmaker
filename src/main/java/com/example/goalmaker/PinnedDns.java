package com.example.goalmaker;

import okhttp3.Dns;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PinnedDns implements Dns {
    private final Resolver resolver;
    private final boolean allowPrivateAddresses;
    private final Map<String, List<InetAddress>> pinned = new ConcurrentHashMap<>();

    PinnedDns(boolean allowPrivateAddresses) {
        this(hostname -> List.of(InetAddress.getAllByName(hostname)), allowPrivateAddresses);
    }

    PinnedDns(Resolver resolver, boolean allowPrivateAddresses) {
        this.resolver = resolver;
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        String key = hostname.toLowerCase(Locale.ROOT);
        List<InetAddress> existing = pinned.get(key);
        if (existing != null) return existing;
        List<InetAddress> resolved = resolver.resolve(hostname);
        if (resolved == null || resolved.isEmpty()) throw new UnknownHostException(hostname);
        List<InetAddress> validated = List.copyOf(resolved);
        if (!allowPrivateAddresses) {
            for (InetAddress address : validated) {
                if (privateAddress(address)) {
                    throw new UnknownHostException("url resolves to a private or local network address");
                }
            }
        }
        List<InetAddress> raced = pinned.putIfAbsent(key, validated);
        return raced == null ? validated : raced;
    }

    List<InetAddress> pinnedAddresses(String hostname) {
        return pinned.getOrDefault(hostname.toLowerCase(Locale.ROOT), List.of());
    }

    private static boolean privateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            if (bytes.length != 16) return true;
            if ((bytes[0] & 0xfe) == 0xfc) return true;
            return bytes[0] == 0x20 && bytes[1] == 0x01
                    && bytes[2] == 0x0d && (bytes[3] & 0xff) == 0xb8;
        }
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = bytes.length > 1 ? Byte.toUnsignedInt(bytes[1]) : 0;
        int third = bytes.length > 2 ? Byte.toUnsignedInt(bytes[2]) : 0;
        return first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113);
    }

    @FunctionalInterface
    interface Resolver {
        List<InetAddress> resolve(String hostname) throws UnknownHostException;
    }
}
