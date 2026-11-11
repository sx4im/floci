package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.CompactJws;
import io.floci.aws.core.common.OidcIssuerKeyLookup;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eks.model.ClusterOidcKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the RSA signing material behind each EKS cluster's IRSA OIDC issuer, mints service-account
 * tokens for it, and resolves issuer URLs back to a verification key for STS.
 *
 * <p>The issuer URL is AWS-shaped ({@code https://oidc.eks.<region>.amazonaws.com/id/<id>}) and
 * deliberately cosmetic: nothing dereferences it. Real IRSA has STS fetch the JWKS, but Floci's STS
 * resolves the key in-process through {@link OidcIssuerKeyLookup}, so the URL only has to be a
 * faithful string for trust-policy construction.
 */
@ApplicationScoped
public class EksOidcService implements OidcIssuerKeyLookup {

    private static final Logger LOG = Logger.getLogger(EksOidcService.class);

    public static final String STS_AUDIENCE = "sts.amazonaws.com"; // partition-literal: web-identity audience; no source outside the commercial partition (P9)
    private static final int DEFAULT_TOKEN_LIFETIME_SECONDS = 86400;
    private static final int MAX_TOKEN_LIFETIME_SECONDS = 604800;

    /** The region label in an OIDC issuer host ({@code oidc.eks.<region>.<dns-suffix>}). */
    private static final Pattern ISSUER_REGION_PATTERN =
            Pattern.compile("oidc\\.eks\\.([a-z]{2,4}-[a-z-]+-\\d+)\\.");

    private final StorageBackend<String, ClusterOidcKey> keyStore;
    private final ObjectMapper objectMapper;

    @Inject
    public EksOidcService(StorageFactory storageFactory, ObjectMapper objectMapper) {
        this.keyStore = storageFactory.create("eks", "eks-oidc-keys.json",
                new TypeReference<Map<String, ClusterOidcKey>>() {
                });
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the issuer URL for a newly created cluster. The random id mirrors the 32-hex-character
     * identifier real EKS embeds in the issuer path.
     */
    public String newIssuerUrl(String region) {
        String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return "https://" + AwsEndpoints.host("oidc.eks", region) + "/id/" + id;
    }

    /**
     * Generates and stores the cluster's signing keypair if absent, returning the stored material.
     * Keyed by region and cluster name since #7966, so same-named clusters in different regions
     * keep distinct signing keys: the issuer URL carries the region, and the key for one region's
     * cluster must never overwrite another's. An issuer with no region in it keeps the historical
     * bare cluster-name key.
     */
    public synchronized ClusterOidcKey ensureKey(String clusterName, String issuer) {
        return ensureKey(null, clusterName, issuer);
    }

    /**
     * As {@link #ensureKey}, but scoped to an explicit account. Startup and other work running
     * without a request context must pass the owning account from the cluster record, since the
     * request-scoped store would otherwise resolve to the default account.
     */
    public synchronized ClusterOidcKey ensureKeyForAccount(String accountId, String clusterName,
                                                           String issuer) {
        return ensureKey(accountId, clusterName, issuer);
    }

    private ClusterOidcKey ensureKey(String accountId, String clusterName, String issuer) {
        String region = regionFromIssuer(issuer);
        Optional<ClusterOidcKey> existing = readKey(accountId, region, clusterName);
        if (existing.isPresent() && issuer.equals(existing.get().getIssuer())) {
            return existing.get();
        }

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            ClusterOidcKey key = new ClusterOidcKey(
                    issuer,
                    UUID.randomUUID().toString(),
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            writeKey(accountId, region, clusterName, key);
            LOG.debugv("Generated IRSA OIDC signing key for EKS cluster {0}", clusterName);
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate EKS OIDC signing keypair for cluster "
                    + clusterName + ": RSA unavailable", e);
        }
    }

    /** The region an issuer URL names, or null when it carries none. */
    static String regionFromIssuer(String issuer) {
        if (issuer == null) {
            return null;
        }
        Matcher matcher = ISSUER_REGION_PATTERN.matcher(issuer);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Storage key for a cluster's signing key: region-qualified when the region is known. */
    private static String oidcKey(String region, String clusterName) {
        return region == null ? clusterName : region + "/" + clusterName;
    }

    /**
     * The signing key for {@code clusterName} in {@code region}: the region-qualified record
     * first, then a key stored under the bare name before #7966. A legacy hit whose issuer
     * names this region is migrated to the qualified key on read; a legacy key for another
     * region belongs to the same-named cluster there and is not visible here.
     */
    private Optional<ClusterOidcKey> readKey(String accountId, String region, String clusterName) {
        if (region != null) {
            Optional<ClusterOidcKey> qualified = readKeyAt(accountId, oidcKey(region, clusterName));
            if (qualified.isPresent()) {
                return qualified;
            }
            Optional<ClusterOidcKey> legacy = readKeyAt(accountId, clusterName);
            if (legacy.isPresent() && region.equals(regionFromIssuer(legacy.get().getIssuer()))) {
                writeKeyAt(accountId, oidcKey(region, clusterName), legacy.get());
                deleteKeyAt(accountId, clusterName);
                return legacy;
            }
            return Optional.empty();
        }
        return readKeyAt(accountId, clusterName);
    }

    private Optional<ClusterOidcKey> readKeyAt(String accountId, String key) {
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            return aware.getForAccount(accountId, key);
        }
        return keyStore.get(key);
    }

    private void writeKey(String accountId, String region, String clusterName, ClusterOidcKey key) {
        writeKeyAt(accountId, oidcKey(region, clusterName), key);
        if (region != null) {
            // A same-region legacy bare key is this cluster's old key: drop it once the
            // region-qualified record exists, so no stale copy survives the rotation.
            Optional<ClusterOidcKey> legacy = readKeyAt(accountId, clusterName);
            if (legacy.isPresent() && region.equals(regionFromIssuer(legacy.get().getIssuer()))) {
                deleteKeyAt(accountId, clusterName);
            }
        }
    }

    private void writeKeyAt(String accountId, String key, ClusterOidcKey value) {
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            aware.putForAccount(accountId, key, value);
            return;
        }
        keyStore.put(key, value);
    }

    private void deleteKeyAt(String accountId, String key) {
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            aware.deleteForAccount(accountId, key);
            return;
        }
        keyStore.delete(key);
    }

    /**
     * Drops the signing key for {@code clusterName} in {@code region}. The legacy bare key is
     * removed only when it names a key for this region: a bare key from another region is a
     * different cluster's key that still restores.
     */
    public void deleteKey(String region, String clusterName) {
        keyStore.delete(oidcKey(region, clusterName));
        if (region != null) {
            Optional<ClusterOidcKey> legacy = keyStore.get(clusterName);
            if (legacy.isPresent() && region.equals(regionFromIssuer(legacy.get().getIssuer()))) {
                keyStore.delete(clusterName);
            }
        }
    }

    public Optional<ClusterOidcKey> findKeyByCluster(String region, String clusterName) {
        return readKey(null, region, clusterName);
    }

    @Override
    public Optional<RSAPublicKey> findVerificationKey(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return Optional.empty();
        }
        // Scans every account, not just the caller's. An issuer URL is globally unique, and the
        // account calling AssumeRoleWithWebIdentity need not be the one that owns the cluster.
        // Account-scoped scanning would leave the key unfound, and an unfound issuer is treated as
        // a third-party provider, so the token would be accepted with no validation at all.
        return allKeys().stream()
                .filter(key -> issuer.equals(key.getIssuer()))
                .findFirst()
                .map(key -> toPublicKey(key.getPublicKey()));
    }

    private List<ClusterOidcKey> allKeys() {
        if (keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            return aware.scanAllAccounts();
        }
        return keyStore.scan(k -> true);
    }

    /**
     * Mints an RS256-signed service-account token for {@code namespace}/{@code serviceAccount},
     * shaped like the projected token a pod would present to STS.
     */
    public String mintServiceAccountToken(String clusterName, String issuer, String namespace,
                                          String serviceAccount, String audience, Integer lifetimeSeconds) {
        if (namespace == null || namespace.isBlank()) {
            throw new AwsException("InvalidParameterException", "namespace is required", 400);
        }
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw new AwsException("InvalidParameterException", "serviceAccount is required", 400);
        }
        int lifetime = resolveLifetime(lifetimeSeconds);
        String aud = audience == null || audience.isBlank() ? STS_AUDIENCE : audience;

        ClusterOidcKey key = ensureKey(clusterName, issuer);
        long now = Instant.now().getEpochSecond();
        long exp = now + lifetime;
        String subject = "system:serviceaccount:" + namespace + ":" + serviceAccount;

        return CompactJws.sign(writeJson(buildHeader(key.getKeyId())),
                writeJson(buildClaims(issuer, aud, subject, namespace, serviceAccount, now, exp)),
                toPrivateKey(key.getPrivateKey()), "SHA256withRSA");
    }

    private int resolveLifetime(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_TOKEN_LIFETIME_SECONDS;
        }
        return Math.min(requested, MAX_TOKEN_LIFETIME_SECONDS);
    }

    private ObjectNode buildHeader(String keyId) {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", keyId);
        return header;
    }

    private ObjectNode buildClaims(String issuer, String audience, String subject, String namespace,
                                   String serviceAccount, long issuedAt, long expiresAt) {
        ObjectNode claims = objectMapper.createObjectNode();
        claims.put("iss", issuer);
        claims.putArray("aud").add(audience);
        claims.put("sub", subject);
        claims.put("iat", issuedAt);
        claims.put("nbf", issuedAt);
        claims.put("exp", expiresAt);
        claims.put("jti", UUID.randomUUID().toString());

        ObjectNode kubernetes = claims.putObject("kubernetes.io");
        kubernetes.put("namespace", namespace);
        kubernetes.putObject("serviceaccount").put("name", serviceAccount);
        return claims;
    }

    /**
     * Serializes a claim set to bytes. Jackson handles escaping, including the control characters a
     * hand-rolled escape would miss and which would otherwise produce a structurally invalid token.
     */
    private byte[] writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize EKS OIDC token claims", e);
        }
    }

    RSAPublicKey toPublicKey(String encoded) {
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(encoded));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load EKS OIDC public key", e);
        }
    }

    private PrivateKey toPrivateKey(String encoded) {
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded));
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load EKS OIDC private key", e);
        }
    }

    /**
     * Exports the cluster's RSA signing key in PKCS#8 PEM format for the k3s API server's
     * {@code --service-account-signing-key-file} argument.
     */
    public String exportSigningKeyPem(ClusterOidcKey key) {
        if (key == null || key.getPrivateKey() == null) {
            throw new IllegalArgumentException("ClusterOidcKey and its private key must not be null");
        }
        return toPem("PRIVATE KEY", key.getPrivateKey());
    }

    /**
     * Exports the cluster's RSA public key in X.509 PEM format for the k3s API server's
     * {@code --service-account-key-file} argument.
     */
    public String exportPublicKeyPem(ClusterOidcKey key) {
        if (key == null || key.getPublicKey() == null) {
            throw new IllegalArgumentException("ClusterOidcKey and its public key must not be null");
        }
        return toPem("PUBLIC KEY", key.getPublicKey());
    }

    private static String toPem(String type, String base64) {
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        pem.append("-----END ").append(type).append("-----\n");
        return pem.toString();
    }
}
