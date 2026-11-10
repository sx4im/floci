package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.Map;
import java.util.Optional;

/**
 * Shared commands and network resolution helpers for installing and starting
 * the link-local IMDS endpoint (169.254.169.254:80) inside containers.
 */
public final class Ec2MetadataProxy {

    /**
     * Run first in the shells that install and start these proxies and in the sshd install probe.
     * {@code docker exec} hands the shell the image's environment, so an application's library
     * search path or Python installation would otherwise be what the package manager, {@code iptables}
     * and {@code curl} load. Only the helper shell changes; the container's configured environment
     * and its processes keep these variables.
     */
    static final String CLEAR_APPLICATION_PATHS = "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME";

    private Ec2MetadataProxy() {}

    public static String[] installCommand() {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                CLEAR_APPLICATION_PATHS,
                "if command -v ip >/dev/null 2>&1 && command -v iptables >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends iproute2 iptables curl ca-certificates >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                // --allowerasing lets dnf swap the curl-minimal that
                // public.ecr.aws/amazonlinux/amazonlinux:2023 ships by default for the full
                // curl package this proxy needs. Without it, dnf aborts the whole transaction
                // on a curl/curl-minimal conflict and iproute+iptables never install either, even
                // though neither of them conflicts with anything.
                "  dnf install -y --allowerasing iproute iptables curl ca-certificates >/dev/null",
                // Same gap as the sshd probe: Amazon Linux 2 has only yum, so on an instance
                // launched from ami-amazonlinux2 this chain reached its else branch and exited 1
                // with "No supported package manager found for IMDS proxy dependencies",
                // leaving the instance without a link-local IMDS endpoint.
                "elif command -v yum >/dev/null 2>&1; then",
                "  yum install -y iproute iptables curl ca-certificates >/dev/null",
                "elif command -v apk >/dev/null 2>&1; then",
                "  apk add --no-cache iproute2 iptables curl ca-certificates >/dev/null",
                "else",
                "  echo 'No supported package manager found for IMDS proxy dependencies' >&2",
                "  exit 1",
                "fi")};
    }

    public static String[] startCommand(String flociHost, int imdsPort) {
        return startCommand("imds", "169.254.169.254", 80, flociHost, imdsPort,
                imdsProbeCommand("http://169.254.169.254"));
    }

    /**
     * Ready once an IMDSv2 token reads the instance ID, which also holds on an instance whose
     * HttpTokens is required. A refused or empty token ends the attempt rather than falling back to
     * a tokenless read, which an empty token header would otherwise become. Both requests bypass
     * any HTTP proxy the image configures: the check is of the link-local path itself.
     */
    static String imdsProbeCommand(String endpoint) {
        return "token=$(curl -fsS --noproxy '*' --max-time 1 -X PUT "
                + "-H 'X-aws-ec2-metadata-token-ttl-seconds: 60' " + endpoint + "/latest/api/token)"
                + " && [ -n \"$token\" ]"
                + " && curl -fsS --noproxy '*' --max-time 1 -H \"X-aws-ec2-metadata-token: $token\" "
                + endpoint + "/latest/meta-data/instance-id >/dev/null && exit 0";
    }

    public static String[] podIdentityStartCommand(String flociHost, int flociPort) {
        return startCommand("pod-identity", "169.254.170.23", 80, flociHost, flociPort,
                podIdentityProbeCommand("http://169.254.170.23"));
    }

    /**
     * Ready once the relay answers at all: without a pod's token Floci answers 400, which still
     * proves the redirect relays. Any HTTP proxy the image configures is bypassed, since a proxy's own
     * answer would pass the same check.
     */
    static String podIdentityProbeCommand(String endpoint) {
        return "[ \"$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 1 " + endpoint
                + "/v1/credentials)\" != \"000\" ] && exit 0";
    }

    /**
     * Redirects the link-local address to the Floci host with an iptables NAT rule instead of
     * listening on it: a listener on one address still blocks any later bind of the wildcard
     * address on the same port, so the old socat listener kept guest processes from listening on
     * port 80 at all. The NAT rule leaves port 80 free. The per-proxy chain makes a re-run
     * replace the old destination instead of stacking a second rule in front of it, so a restart
     * that moved the Floci host points the redirect at the new one.
     */
    public static String[] startCommand(String name, String bindIp, int bindPort,
                                        String targetHost, int targetPort, String probeCommand) {
        String chain = chainName(name);
        String jump = "-d " + bindIp + " -p tcp --dport " + bindPort + " -j " + chain;
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                CLEAR_APPLICATION_PATHS,
                "ip addr show dev lo | grep -q '" + bindIp + "/32' || ip addr add " + bindIp + "/32 dev lo",
                // The chain is per proxy so a re-run replaces the destination instead of
                // stacking a second DNAT rule, which would keep sending traffic to the old host.
                "iptables -t nat -N " + chain + " 2>/dev/null || true",
                "iptables -t nat -C OUTPUT " + jump + " 2>/dev/null || iptables -t nat -A OUTPUT " + jump,
                "iptables -t nat -F " + chain,
                "iptables -t nat -A " + chain + " -j DNAT --to-destination " + targetHost + ":" + targetPort,
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do",
                "  " + probeCommand,
                "  sleep 1",
                "done",
                "exit 1")};
    }

    /** The iptables chain a proxy's redirect lives in, sanitized to the names iptables accepts. */
    static String chainName(String name) {
        String chain = "floci-" + name.replaceAll("[^A-Za-z0-9_-]", "-");
        return chain.length() <= 29 ? chain : chain.substring(0, 29);
    }

    public static Optional<String> preferredMetadataSourceIp(Map<String, ContainerNetwork> networks) {
        if (networks == null || networks.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> configuredNetworkIp = networks.entrySet().stream()
                .filter(entry -> !"bridge".equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
        if (configuredNetworkIp.isPresent()) {
            return configuredNetworkIp;
        }
        ContainerNetwork bridge = networks.get("bridge");
        if (bridge != null && bridge.getIpAddress() != null && !bridge.getIpAddress().isBlank()) {
            return Optional.of(bridge.getIpAddress());
        }
        return networks.values().stream()
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
    }
}
