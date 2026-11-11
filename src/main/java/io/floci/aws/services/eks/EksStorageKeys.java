package io.floci.aws.services.eks;

/**
 * Storage keys for EKS state. Cluster names are unique only within a Region, so every key a
 * cluster owns carries its Region first: {@code <region>/<name>}. Regions and cluster names
 * contain no slash, so the key is unambiguous, and a bare {@code <name>} key is the pre-change
 * shape a stored record may still sit under until its first read migrates it.
 */
final class EksStorageKeys {

    private EksStorageKeys() {
    }

    static String clusterKey(String region, String clusterName) {
        return region + "/" + clusterName;
    }

    static String clusterKeyPrefix(String region, String clusterName) {
        return clusterKey(region, clusterName) + "/";
    }

    static String nodeGroupKey(String region, String clusterName, String nodegroupName) {
        return clusterKeyPrefix(region, clusterName) + nodegroupName;
    }

    static String fargateProfileKey(String region, String clusterName, String fargateProfileName) {
        return clusterKeyPrefix(region, clusterName) + fargateProfileName;
    }
}
