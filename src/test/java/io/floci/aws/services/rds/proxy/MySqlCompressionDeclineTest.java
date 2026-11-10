package io.floci.aws.services.rds.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy relays the connection phase and commands by parsing uncompressed packet framing, so it
 * cannot pass compressed packets through. It must therefore decline compression at the handshake
 * instead of advertising a capability it cannot honor (see #7938).
 */
class MySqlCompressionDeclineTest {

    private static final int CLIENT_PROTOCOL_41 = 0x0200;
    private static final int CLIENT_SECURE_CONNECTION = 0x8000;
    private static final int CLIENT_SSL = 0x0800;
    private static final int CLIENT_COMPRESS = 0x0020;
    private static final int CLIENT_PLUGIN_AUTH = 0x0008_0000;
    private static final int CLIENT_ZSTD_COMPRESSION_ALGORITHM = 0x0400_0000;
    private static final int COM_QUERY = 0x03;
    private static final String MYSQL_NATIVE_PASSWORD = "mysql_native_password";
    private static final byte[] OK_PAYLOAD = {0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00};

    /** Fails a login that opens a second backend connection; only an IAM auth switch may. */
    private static final PostgresProtocolHandler.BackendConnector NO_RECONNECT = () -> {
        throw new IOException("unexpected second backend connection");
    };

    /**
     * A backend that advertises compression must not make the proxy advertise it: a client that
     * negotiates compression sends compressed packets the relay mis-frames, and the connection hangs
     * with the backend idle and the client waiting (see #7938). The client falls back to the
     * uncompressed protocol and the login plus one query round-trip cleanly.
     */
    @Test
    void doesNotAdvertiseCompressionToTheClient() throws Exception {
        byte[] nonce = fixedNonce();
        AtomicReference<byte[]> backendResponsePayload = new AtomicReference<>();
        int backendCaps = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH
                | CLIENT_COMPRESS | CLIENT_ZSTD_COMPRESSION_ALGORITHM;

        // Bound to IPv6 loopback explicitly: a wildcard ServerSocket plus "localhost" resolves
        // inconsistently for Java sockets in some runtimes, and the connection never arrives.
        try (ServerSocket backendServer = new ServerSocket(0, 50, InetAddress.getByName("::1"));
             ServerSocket clientServer = new ServerSocket(0, 50, InetAddress.getByName("::1"))) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = backendServer.accept()) {
                    socket.setSoTimeout(5_000);
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    out.write(buildHandshakeV10(nonce, MYSQL_NATIVE_PASSWORD, backendCaps));
                    out.flush();

                    byte[] responseRaw = readMysqlPacketRaw(in);
                    assertNotNull(responseRaw);
                    byte[] payload = new byte[responseRaw.length - 4];
                    System.arraycopy(responseRaw, 4, payload, 0, payload.length);
                    backendResponsePayload.set(payload);

                    writeMysqlPacket(out, 2, OK_PAYLOAD);
                    out.flush();

                    byte[] command = readMysqlPacketRaw(in);
                    assertNotNull(command);
                    assertEquals(COM_QUERY, command[4] & 0xFF, "expected an uncompressed COM_QUERY");
                    assertEquals("select 1",
                            new String(command, 5, command.length - 5, StandardCharsets.UTF_8));
                    writeMysqlPacket(out, 1, OK_PAYLOAD);
                    out.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("::1", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("::1", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        // Plaintext master login: the TLS, SigV4 and binding arguments are only
                        // touched on the TLS/IAM paths, so they stay null here.
                        MySqlProtocolHandler.handleAuth(
                                proxyClient, backend, NO_RECONNECT, "admin", "secret",
                                false, null, null,
                                (user, pass) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000,
                                username -> true, null);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream clientIn = ourClient.getInputStream();
                OutputStream clientOut = ourClient.getOutputStream();

                byte[] serverHandshake = readMysqlPacketRaw(clientIn);
                assertNotNull(serverHandshake);
                int forwarded = capabilityFlags(serverHandshake);
                assertEquals(0, forwarded & CLIENT_COMPRESS,
                        "the proxy must not advertise CLIENT_COMPRESS, or a compressing client hangs");
                assertEquals(0, forwarded & CLIENT_ZSTD_COMPRESSION_ALGORITHM,
                        "the proxy must not advertise zstd compression either");
                assertTrue((forwarded & CLIENT_SSL) != 0, "CLIENT_SSL must still be forced on");
                assertTrue((forwarded & CLIENT_PROTOCOL_41) != 0, "other capabilities must survive");

                byte[] scramble = scrambleNativePassword("secret", nonce);
                clientOut.write(buildHandshakeResponse41("admin", scramble, (byte) 1));
                clientOut.flush();

                assertNotNull(readMysqlPacketRaw(clientIn));

                ByteArrayOutputStream query = new ByteArrayOutputStream();
                query.write(COM_QUERY);
                query.write("select 1".getBytes(StandardCharsets.UTF_8));
                clientOut.write(wrapPacket(0, query.toByteArray()));
                clientOut.flush();
                assertNotNull(readMysqlPacketRaw(clientIn));

                ourClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertFalse(authThread.isAlive(), "authThread did not terminate");
                assertFalse(backendThread.isAlive(), "backendThread did not terminate");
            }
        }

        assertEquals("admin", extractUsername(backendResponsePayload.get()));
    }

    @Test
    void clearCompressionCapabilitiesClearsOnlyCompressionBits() throws Exception {
        int caps = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH
                | CLIENT_SSL | CLIENT_COMPRESS | CLIENT_ZSTD_COMPRESSION_ALGORITHM;
        byte[] handshake = buildHandshakeV10(fixedNonce(), MYSQL_NATIVE_PASSWORD, caps);
        byte[] expected = buildHandshakeV10(fixedNonce(), MYSQL_NATIVE_PASSWORD,
                caps & ~CLIENT_COMPRESS & ~CLIENT_ZSTD_COMPRESSION_ALGORITHM);

        MySqlProtocolHandler.clearCompressionCapabilities(handshake);

        assertArrayEquals(expected, handshake);
    }

    @Test
    void clearCompressionCapabilitiesLeavesUncompressedHandshakeUntouched() throws Exception {
        int caps = CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH | CLIENT_SSL;
        byte[] handshake = buildHandshakeV10(fixedNonce(), MYSQL_NATIVE_PASSWORD, caps);
        byte[] before = Arrays.copyOf(handshake, handshake.length);

        MySqlProtocolHandler.clearCompressionCapabilities(handshake);

        assertArrayEquals(before, handshake);
    }

    @Test
    void clearCompressionCapabilitiesToleratesTruncatedHandshake() {
        byte[] shortHandshake = {0x10, 0x00, 0x00, 0x00, 0x0A, '8', '.', '0', 0x00};
        byte[] before = Arrays.copyOf(shortHandshake, shortHandshake.length);

        MySqlProtocolHandler.clearCompressionCapabilities(shortHandshake);

        assertArrayEquals(before, shortHandshake);
    }

    // ── MySQL Handshake V10 (server -> client) ──────────────────────────────

    private static byte[] buildHandshakeV10(byte[] nonce, String authPlugin, int capabilities)
            throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(10); // protocol version
        payload.write("8.0.34".getBytes(StandardCharsets.UTF_8));
        payload.write(0);
        writeInt32LE(payload, 1); // connection id
        payload.write(nonce, 0, 8); // auth-plugin-data part 1
        payload.write(0); // filler
        writeInt16LE(payload, capabilities & 0xFFFF);
        payload.write(0x21); // charset
        writeInt16LE(payload, 0x0002); // status flags
        writeInt16LE(payload, (capabilities >>> 16) & 0xFFFF);
        payload.write(authPlugin == null ? 0 : 21);
        payload.write(new byte[10]); // reserved
        payload.write(nonce, 8, 12); // auth-plugin-data part 2 (12 bytes)
        payload.write(0); // null terminator
        if (authPlugin != null) {
            payload.write(authPlugin.getBytes(StandardCharsets.UTF_8));
            payload.write(0);
        }

        return wrapPacket(0, payload.toByteArray());
    }

    // ── MySQL HandshakeResponse41 (client -> server) ────────────────────────

    private static byte[] buildHandshakeResponse41(String username, byte[] scramble, byte sequence)
            throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt32LE(payload, CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION);
        writeInt32LE(payload, 0); // max packet size
        payload.write(0x21); // charset
        payload.write(new byte[23]); // reserved
        payload.write(username.getBytes(StandardCharsets.UTF_8));
        payload.write(0);
        payload.write(scramble.length); // CLIENT_SECURE_CONNECTION: 1-byte length prefix
        payload.write(scramble);

        return wrapPacket(sequence, payload.toByteArray());
    }

    private static String extractUsername(byte[] payload) {
        int i = 4 + 4 + 1 + 23; // caps + max-packet-size + charset + reserved
        int start = i;
        while (i < payload.length && payload[i] != 0) {
            i++;
        }
        return new String(payload, start, i - start, StandardCharsets.UTF_8);
    }

    /** Full 32-bit capability flags of a Handshake V10 packet: lower 2 bytes plus upper 2 bytes. */
    private static int capabilityFlags(byte[] handshakeRaw) {
        int i = 4 + 1; // header + protocol version
        while (i < handshakeRaw.length && handshakeRaw[i] != 0) {
            i++;
        }
        i++; // null terminator
        i += 4; // connection id
        i += 8; // auth-plugin-data part 1
        i++; // filler
        int lower = (handshakeRaw[i] & 0xFF) | ((handshakeRaw[i + 1] & 0xFF) << 8);
        int upper = (handshakeRaw[i + 5] & 0xFF) | ((handshakeRaw[i + 6] & 0xFF) << 8);
        return lower | (upper << 16);
    }

    private static byte[] fixedNonce() {
        byte[] nonce = new byte[20];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (i + 1);
        }
        return nonce;
    }

    private static byte[] scrambleNativePassword(String password, byte[] nonce) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash1 = sha1.digest(password.getBytes(StandardCharsets.UTF_8));
        sha1.reset();
        byte[] hash2 = sha1.digest(hash1);
        sha1.reset();
        sha1.update(nonce);
        sha1.update(hash2);
        byte[] hash3 = sha1.digest();

        byte[] result = new byte[20];
        for (int i = 0; i < 20; i++) {
            result[i] = (byte) (hash1[i] ^ hash3[i]);
        }
        return result;
    }

    // ── Raw packet helpers ───────────────────────────────────────────────────

    private static byte[] wrapPacket(int sequence, byte[] payload) {
        byte[] raw = new byte[4 + payload.length];
        int len = payload.length;
        raw[0] = (byte) (len & 0xFF);
        raw[1] = (byte) ((len >> 8) & 0xFF);
        raw[2] = (byte) ((len >> 16) & 0xFF);
        raw[3] = (byte) sequence;
        System.arraycopy(payload, 0, raw, 4, payload.length);
        return raw;
    }

    private static void writeMysqlPacket(OutputStream out, int sequence, byte[] payload) throws IOException {
        out.write(wrapPacket(sequence, payload));
    }

    private static byte[] readMysqlPacketRaw(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int seq = in.read();
        if ((b0 | b1 | b2 | seq) < 0) {
            return null;
        }
        int length = b0 | (b1 << 8) | (b2 << 16);
        byte[] raw = new byte[4 + length];
        raw[0] = (byte) b0;
        raw[1] = (byte) b1;
        raw[2] = (byte) b2;
        raw[3] = (byte) seq;
        int offset = 4;
        while (offset < raw.length) {
            int n = in.read(raw, offset, raw.length - offset);
            if (n < 0) {
                throw new EOFException("Connection closed while reading MySQL packet");
            }
            offset += n;
        }
        return raw;
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeInt16LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
