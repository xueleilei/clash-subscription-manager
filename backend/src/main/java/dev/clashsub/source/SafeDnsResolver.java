package dev.clashsub.source;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SafeDnsResolver {
    @FunctionalInterface public interface HostLookup { InetAddress[] resolve(String host) throws UnknownHostException; }
    private record Cidr(byte[] address, int bits) {
        static Cidr parse(String text) {
            var parts = text.split("/", -1);
            if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F:.]+")) { throw new IllegalArgumentException("Invalid private address allowlist"); }
            try {
                var bytes = InetAddress.getByName(parts[0]).getAddress();
                int bits = Integer.parseInt(parts[1]);
                if (bits < 0 || bits > bytes.length * 8) { throw new IllegalArgumentException("Invalid private address allowlist"); }
                return new Cidr(bytes, bits);
            } catch (UnknownHostException exception) { throw new IllegalArgumentException("Invalid private address allowlist"); }
        }
        boolean contains(InetAddress value) {
            byte[] bytes = value.getAddress();
            if (address.length != bytes.length) { return false; }
            for (int index = 0; index < bits; index++) {
                int mask = 1 << (7 - index % 8);
                if ((address[index / 8] & mask) != (bytes[index / 8] & mask)) { return false; }
            }
            return true;
        }
    }
    private static final List<Cidr> BLOCKED_V4 = List.of("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24",
            "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4")
            .stream().map(Cidr::parse).toList();
    private static final List<Cidr> BLOCKED_V6 = List.of("2001:db8::/32", "2001::/32", "2001:2::/48", "2001:10::/28", "2002::/16")
            .stream().map(Cidr::parse).toList();
    private static final Cidr FAKE_IP_V4 = Cidr.parse("198.18.0.0/15");
    private static final Pattern NAMED_HOST = Pattern.compile("(?i)[a-z0-9.-]+\\.[a-z][a-z0-9-]*\\.?");
    private final List<Cidr> allowlist;
    private final HostLookup lookup;

    @Autowired public SafeDnsResolver(@Value("${clash.fetch.private-allowlist:}") String allowlist) {
        this(Arrays.stream(allowlist.split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList(), InetAddress::getAllByName);
    }
    public SafeDnsResolver(List<String> allowlist, HostLookup lookup) {
        this.allowlist = allowlist.stream().map(Cidr::parse).toList(); this.lookup = lookup;
    }

    public InetAddress[] resolveAndCheck(String hostname) throws UnknownHostException {
        return resolveAndCheck(hostname, false);
    }

    public InetAddress[] resolveAndCheck(String hostname, boolean viaTrustedProxy) throws UnknownHostException {
        var addresses = lookup.resolve(hostname);
        if (addresses.length == 0) { throw new UnknownHostException(); }
        // A trusted proxy receives the original DNS name, never a direct connection to the synthetic IP.
        // Require a named host so numeric IP spellings cannot gain the Fake-IP exception.
        boolean allowFakeIp = viaTrustedProxy && NAMED_HOST.matcher(hostname).matches();
        for (var address : addresses) {
            if (allowlist.stream().anyMatch(cidr -> cidr.contains(address))) { continue; }
            if (allowFakeIp && FAKE_IP_V4.contains(address)) { continue; }
            boolean blocked = address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress();
            var bytes = address.getAddress();
            if (bytes.length == 4) { blocked |= BLOCKED_V4.stream().anyMatch(cidr -> cidr.contains(address)); }
            else { blocked |= (bytes[0] & 0xe0) != 0x20 || BLOCKED_V6.stream().anyMatch(cidr -> cidr.contains(address)); }
            if (blocked) { throw new FetchPolicyException("UPSTREAM_ADDRESS_BLOCKED"); }
        }
        return addresses.clone();
    }
}
