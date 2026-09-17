package com.billing.license.infrastructure.kms;

/**
 * Key Management Service interface supporting multiple providers
 */
public interface KmsService {
    
    /**
     * Sign data with the managed key
     * @param data data to sign
     * @return signature bytes
     */
    byte[] sign(byte[] data);
    
    /**
     * Verify signature
     * @param data original data
     * @param signature signature bytes
     * @return true if valid
     */
    boolean verify(byte[] data, byte[] signature);

    /**
     * 按 kid 选择公钥验签（A5：多 kid 密钥轮换预留）。
     * 默认实现忽略 kid，直接委派 {@link #verify(byte[], byte[])}，
     * 保证既有实现无需改动即可编译通过、行为不变。
     *
     * @param data      原始数据
     * @param signature 签名
     * @param kid       token header 中的密钥标识，可为 null（走默认公钥）
     * @return true 表示签名有效
     */
    default boolean verify(byte[] data, byte[] signature, String kid) {
        return verify(data, signature);
    }
    
    /**
     * Get public key bytes for distribution
     * @return public key bytes
     */
    byte[] getPublicKey();
    
    /**
     * Get key algorithm name
     * @return algorithm name
     */
    String getAlgorithm();
}
