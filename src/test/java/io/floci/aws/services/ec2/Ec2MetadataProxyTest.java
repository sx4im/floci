package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataProxyTest {

    @Test
    void installCommandContainsSupportedPackageManagers() {
        String[] command = Ec2MetadataProxy.installCommand();
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("command -v ip >/dev/null 2>&1 && command -v iptables >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi"));
        assertTrue(script.contains("apt-get install -y --no-install-recommends iproute2 iptables curl ca-certificates"));
        assertTrue(script.contains("dnf install -y --allowerasing iproute iptables curl ca-certificates"));
        assertTrue(script.contains("yum install -y iproute iptables curl ca-certificates"));
        assertTrue(script.contains("apk add --no-cache iproute2 iptables curl ca-certificates"));
    }

    @Test
    void startCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.startCommand("10.0.0.1", 9169);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        // Attaches address if missing
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo"));
        // Never listens on port 80: a listener would block a guest process binding 0.0.0.0:80.
        assertFalse(script.contains("socat"));
        assertFalse(script.contains("TCP-LISTEN"));
        // Redirects the link-local address to the Floci host with a NAT rule instead, so port 80 stays free.
        assertTrue(script.contains("iptables -t nat -N floci-imds 2>/dev/null || true"));
        assertTrue(script.contains("iptables -t nat -C OUTPUT -d 169.254.169.254 -p tcp --dport 80 -j floci-imds 2>/dev/null"
                + " || iptables -t nat -A OUTPUT -d 169.254.169.254 -p tcp --dport 80 -j floci-imds"));
        assertTrue(script.contains("iptables -t nat -F floci-imds"));
        assertTrue(script.contains("iptables -t nat -A floci-imds -j DNAT --to-destination 10.0.0.1:9169"));
        // Verifies through an IMDSv2 token even when tokenless access is refused.
        assertTrue(script.contains("-X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'"));
        assertTrue(script.contains("http://169.254.169.254/latest/api/token"));
        assertTrue(script.contains("-H \"X-aws-ec2-metadata-token: $token\" http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void podIdentityStartCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.podIdentityStartCommand("10.0.0.1", 4566);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.170.23/32' || ip addr add 169.254.170.23/32 dev lo"));
        assertFalse(script.contains("socat"));
        assertFalse(script.contains("TCP-LISTEN"));
        assertTrue(script.contains("iptables -t nat -N floci-pod-identity 2>/dev/null || true"));
        assertTrue(script.contains("iptables -t nat -C OUTPUT -d 169.254.170.23 -p tcp --dport 80 -j floci-pod-identity 2>/dev/null"
                + " || iptables -t nat -A OUTPUT -d 169.254.170.23 -p tcp --dport 80 -j floci-pod-identity"));
        assertTrue(script.contains("iptables -t nat -F floci-pod-identity"));
        assertTrue(script.contains("iptables -t nat -A floci-pod-identity -j DNAT --to-destination 10.0.0.1:4566"));
        assertTrue(script.contains("http://169.254.170.23/v1/credentials"));
    }

    @Test
    void preferredMetadataSourceIpPrefersConfiguredNetworkOverBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");
        ContainerNetwork vpc = new ContainerNetwork();
        vpc.withIpv4Address("10.0.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge, "custom-net", vpc));
        assertTrue(ip.isPresent());
        assertEquals("10.0.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge));
        assertTrue(ip.isPresent());
        assertEquals("172.17.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpReturnsEmptyWhenNoNetworks() {
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(null).isEmpty());
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(Map.of()).isEmpty());
    }

    @Test
    void installCommandClearsApplicationPathsBeforeThePackageManagerRuns(@TempDir Path directory)
            throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path record = directory.resolve("tools");
        Path arguments = directory.resolve("arguments");
        HelperShellFixture.writeTool(bin, "dnf", HelperShellFixture.checkedToolEnvironment()
                + "printf '%s\\n' \"$*\" > \"$TEST_ARGUMENTS\"\n");

        assertEquals(0, HelperShellFixture.run(Ec2MetadataProxy.installCommand(), directory,
                HelperShellFixture.applicationEnvironment(Map.of("PATH", bin.toString(),
                        "TEST_RECORD", record.toString(), "TEST_ARGUMENTS", arguments.toString()))),
                HelperShellFixture.output(directory));

        assertEquals("dnf\n", Files.readString(record));
        assertEquals("install -y --allowerasing iproute iptables curl ca-certificates\n",
                Files.readString(arguments));
    }

    @Test
    void startCommandClearsApplicationPathsBeforeAddressProxyAndProbeTools(@TempDir Path directory)
            throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path record = directory.resolve("tools");
        Path proxyReady = directory.resolve("proxy-ready");
        HelperShellFixture.writeTool(bin, "ip", HelperShellFixture.checkedToolEnvironment()
                + "printf '127.0.0.1/32\\n'\n");
        HelperShellFixture.writeTool(bin, "iptables", iptablesTool());
        HelperShellFixture.writeTool(bin, "curl", HelperShellFixture.checkedToolEnvironment()
                + "for attempt in 1 2 3 4 5 6 7 8 9 10; do\n"
                + "  [ -f \"$TEST_PROXY_READY\" ] && exit 0\n"
                + "  /bin/sleep 0.1\n"
                + "done\nexit 1\n");
        String name = "unit-" + UUID.randomUUID();
        Path state = directory.resolve("iptables-state");
        try {
            String[] command = Ec2MetadataProxy.startCommand(
                    name, "127.0.0.1", 80, "127.0.0.1", 9169, "curl fixture && exit 0");
            assertEquals(0, HelperShellFixture.run(command, directory,
                    HelperShellFixture.applicationEnvironment(Map.of("PATH", bin + ":" + System.getenv("PATH"),
                            "TEST_RECORD", record.toString(), "TEST_PROXY_READY", proxyReady.toString(),
                            "TEST_IPTABLES_STATE", state.toString()))),
                    HelperShellFixture.output(directory));
            assertEquals(Set.of("ip", "iptables", "curl"), Set.copyOf(Files.readAllLines(record)));
        } finally {
            Files.deleteIfExists(state);
        }
    }

    @Test
    void readinessProbeReadsTheInstanceIdWithATokenWhenTheInstanceRequiresOne(@TempDir Path directory)
            throws Exception {
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start();
            requireTokens(fixture.instance());

            assertEquals(0, HelperShellFixture.run(probe(fixture.endpoint()), directory, Map.of()),
                    HelperShellFixture.output(directory));
        }
    }

    /** An image's proxy settings are for its application; the probe checks the link-local path itself. */
    @Test
    void readinessProbeIgnoresTheImagesHttpProxy(@TempDir Path directory) throws Exception {
        List<String> proxied = new CopyOnWriteArrayList<>();
        HttpServer proxy = failingProxy(proxied);
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start();
            requireTokens(fixture.instance());

            int result = HelperShellFixture.run(probe(fixture.endpoint()), directory, proxyEnvironment(proxy));

            assertEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(List.of(), proxied);
        } finally {
            proxy.stop(0);
        }
    }

    /**
     * The pod identity relay answers 400 without a pod token, which still proves the redirect
     * relays. A proxy that answered in its place would look the same to the status check, so the
     * probe must not go through one.
     */
    @Test
    void podIdentityProbeIgnoresTheImagesHttpProxy(@TempDir Path directory) throws Exception {
        List<String> proxied = new CopyOnWriteArrayList<>();
        AtomicInteger relayed = new AtomicInteger();
        HttpServer proxy = null;
        HttpServer relay = null;
        try {
            proxy = failingProxy(proxied);
            relay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            relay.createContext("/v1/credentials", exchange -> {
                relayed.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
            });
            relay.start();
            String[] probe = {"sh", "-c",
                    Ec2MetadataProxy.podIdentityProbeCommand("http://127.0.0.1:" + relay.getAddress().getPort())};

            int result = HelperShellFixture.run(probe, directory, proxyEnvironment(proxy));

            assertEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(1, relayed.get(), HelperShellFixture.output(directory));
            assertEquals(List.of(), proxied);
        } finally {
            if (relay != null) {
                relay.stop(0);
            }
            if (proxy != null) {
                proxy.stop(0);
            }
        }
    }

    /**
     * Floci's IMDS issues a token for every valid request, so the refused and empty token answers
     * come from a stand-in that records what the probe sent.
     */
    @ParameterizedTest
    @CsvSource({"403, rejected", "200, ''"})
    void readinessProbeNeverFallsBackToATokenlessRead(int tokenStatus, String tokenBody, @TempDir Path directory)
            throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = metadataStandIn(requests, attempt -> tokenStatus, tokenBody);
        try {
            int result = HelperShellFixture.run(probe("http://127.0.0.1:" + server.getAddress().getPort()),
                    directory, Map.of());

            assertNotEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(List.of("PUT token 60"), requests, HelperShellFixture.output(directory));
        } finally {
            server.stop(0);
        }
    }

    /**
     * The proxy's first probes run before the redirect answers, so a refused token has to end only
     * that attempt: the generated start script, under {@code set -eu}, retries until a token is
     * issued.
     */
    @Test
    void startScriptRetriesTheTokenProbeUntilATokenIsIssued(@TempDir Path directory) throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        HelperShellFixture.writeTool(bin, "ip", "printf '127.0.0.1/32\\n'\n");
        HelperShellFixture.writeTool(bin, "iptables", iptablesTool());
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = metadataStandIn(requests, attempt -> attempt == 1 ? 403 : 200, "fixture-token");
        String name = "unit-" + UUID.randomUUID();
        Path state = directory.resolve("iptables-state");
        Path record = directory.resolve("tools");
        Path proxyReady = directory.resolve("proxy-ready");
        try {
            String[] command = Ec2MetadataProxy.startCommand(name, "127.0.0.1", 80, "127.0.0.1", 9169,
                    Ec2MetadataProxy.imdsProbeCommand("http://127.0.0.1:" + server.getAddress().getPort()));

            int result = HelperShellFixture.run(command, directory,
                    HelperShellFixture.applicationEnvironment(Map.of("PATH", bin + ":" + System.getenv("PATH"),
                            "TEST_RECORD", record.toString(), "TEST_PROXY_READY", proxyReady.toString(),
                            "TEST_IPTABLES_STATE", state.toString())));

            assertEquals(0, result, HelperShellFixture.output(directory));
            assertEquals(List.of("PUT token 60", "PUT token 60", "GET metadata fixture-token"), requests,
                    HelperShellFixture.output(directory));
        } finally {
            server.stop(0);
            Files.deleteIfExists(state);
        }
    }

    /**
     * A restart re-runs the generated script, and the redirect must not stack: a duplicate OUTPUT
     * jump is harmless, but a duplicate DNAT rule would break the replacement test below, which
     * depends on exactly one rule naming the current target.
     */
    @Test
    void startScriptInstallsTheRedirectOnceWhenRunTwice(@TempDir Path directory) throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path state = directory.resolve("iptables-state");
        Path record = directory.resolve("tools");
        Path proxyReady = directory.resolve("proxy-ready");
        HelperShellFixture.writeTool(bin, "ip", "printf '127.0.0.1/32\\n'\n");
        HelperShellFixture.writeTool(bin, "iptables", iptablesTool());
        String name = "unit-" + UUID.randomUUID();
        Map<String, String> environment = HelperShellFixture.applicationEnvironment(Map.of(
                "PATH", bin + ":" + System.getenv("PATH"),
                "TEST_RECORD", record.toString(), "TEST_PROXY_READY", proxyReady.toString(),
                "TEST_IPTABLES_STATE", state.toString()));
        String probe = "[ -f '" + proxyReady + "' ] && exit 0";
        String[] command = Ec2MetadataProxy.startCommand(name, "127.0.0.1", 80, "127.0.0.1", 9169, probe);

        assertEquals(0, HelperShellFixture.run(command, directory, environment), HelperShellFixture.output(directory));
        assertEquals(0, HelperShellFixture.run(command, directory, environment), HelperShellFixture.output(directory));

        List<String> rules = Files.readAllLines(state);
        assertEquals(2, rules.size(), "expected one OUTPUT jump and one DNAT rule, got: " + rules);
        assertTrue(rules.contains("OUTPUT|-d 127.0.0.1 -p tcp --dport 80 -j " + Ec2MetadataProxy.chainName(name)));
        assertTrue(rules.contains(Ec2MetadataProxy.chainName(name) + "|-j DNAT --to-destination 127.0.0.1:9169"));
    }

    /**
     * A restart can move the Floci host, so a re-run with a new target must replace the old
     * redirect rather than leave it in place: the first DNAT rule would win and send metadata
     * traffic to a host that no longer answers.
     */
    @Test
    void startScriptReplacesTheRedirectWhenTheTargetMoves(@TempDir Path directory) throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path state = directory.resolve("iptables-state");
        Path record = directory.resolve("tools");
        Path proxyReady = directory.resolve("proxy-ready");
        HelperShellFixture.writeTool(bin, "ip", "printf '127.0.0.1/32\\n'\n");
        HelperShellFixture.writeTool(bin, "iptables", iptablesTool());
        String name = "unit-" + UUID.randomUUID();
        Map<String, String> environment = HelperShellFixture.applicationEnvironment(Map.of(
                "PATH", bin + ":" + System.getenv("PATH"),
                "TEST_RECORD", record.toString(), "TEST_PROXY_READY", proxyReady.toString(),
                "TEST_IPTABLES_STATE", state.toString()));
        String probe = "[ -f '" + proxyReady + "' ] && exit 0";

        String[] first = Ec2MetadataProxy.startCommand(name, "127.0.0.1", 80, "127.0.0.1", 9169, probe);
        assertEquals(0, HelperShellFixture.run(first, directory, environment), HelperShellFixture.output(directory));
        String[] second = Ec2MetadataProxy.startCommand(name, "127.0.0.1", 80, "127.0.0.2", 9169, probe);
        assertEquals(0, HelperShellFixture.run(second, directory, environment), HelperShellFixture.output(directory));

        List<String> rules = Files.readAllLines(state);
        assertEquals(2, rules.size(), "expected one OUTPUT jump and one DNAT rule, got: " + rules);
        assertTrue(rules.contains("OUTPUT|-d 127.0.0.1 -p tcp --dport 80 -j " + Ec2MetadataProxy.chainName(name)));
        assertTrue(rules.contains(Ec2MetadataProxy.chainName(name) + "|-j DNAT --to-destination 127.0.0.2:9169"));
    }

    /**
     * A fake {@code iptables} that keeps nat rules in $TEST_IPTABLES_STATE, one per line as
     * {@code <target>|<rule>}, so a test can assert exactly which redirects a generated script
     * installs. Installing a DNAT rule marks the endpoint ready, the way the real redirect
     * answering the probe would.
     */
    private static String iptablesTool() {
        return HelperShellFixture.checkedToolEnvironment()
                + "state=\"$TEST_IPTABLES_STATE\"\n"
                + "touch \"$state\"\n"
                + "op=\"$3\"\n"
                + "target=\"$4\"\n"
                + "shift 4\n"
                + "case \"$op\" in\n"
                + "  -N) exit 0;;\n"
                + "  -C) grep -qF \"$target|$*\" \"$state\";;\n"
                + "  -A) printf '%s|%s\\n' \"$target\" \"$*\" >> \"$state\"\n"
                + "      if [ \"$target\" != OUTPUT ]; then : > \"$TEST_PROXY_READY\"; fi\n"
                + "      exit 0;;\n"
                + "  -F) grep -v \"^$target|\" \"$state\" > \"$state.tmp\" || true\n"
                + "      mv \"$state.tmp\" \"$state\";;\n"
                + "esac\n";
    }

    /**
     * Answers token requests with the status {@code tokenStatus} picks for each attempt, serves the
     * instance ID to the issued token and, as an instance allowing IMDSv1 would, to a tokenless
     * read, and records what it was sent.
     */
    private static HttpServer metadataStandIn(List<String> requests, TokenStatus tokenStatus, String tokenBody)
            throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest/api/token", exchange -> {
            requests.add(exchange.getRequestMethod() + " token "
                    + exchange.getRequestHeaders().getFirst(Ec2MetadataTestFixture.TTL_HEADER));
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus.forAttempt(tokenRequests.incrementAndGet()),
                    body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/latest/meta-data/instance-id", exchange -> {
            String token = exchange.getRequestHeaders().getFirst(Ec2MetadataTestFixture.TOKEN_HEADER);
            requests.add(exchange.getRequestMethod() + " metadata " + token);
            byte[] body = "i-fixture".getBytes(StandardCharsets.UTF_8);
            boolean accepted = token == null || !tokenBody.isEmpty() && tokenBody.equals(token);
            exchange.sendResponseHeaders(accepted ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @FunctionalInterface
    private interface TokenStatus {
        int forAttempt(int attempt);
    }

    /** An HTTP proxy that records every request it is sent and answers each with 502. */
    private static HttpServer failingProxy(List<String> proxied) throws Exception {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxied.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        proxy.start();
        return proxy;
    }

    /** The proxy settings an image could carry, with nothing exempted. */
    private static Map<String, String> proxyEnvironment(HttpServer proxy) {
        String proxyUrl = "http://127.0.0.1:" + proxy.getAddress().getPort();
        return Map.of("http_proxy", proxyUrl, "HTTP_PROXY", proxyUrl, "all_proxy", proxyUrl,
                "ALL_PROXY", proxyUrl, "no_proxy", "", "NO_PROXY", "");
    }

    private static String[] probe(String endpoint) {
        return new String[]{"sh", "-c", Ec2MetadataProxy.imdsProbeCommand(endpoint)};
    }

    private static void requireTokens(Instance instance) {
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setHttpTokens("required");
        instance.setMetadataOptions(options);
    }
}
