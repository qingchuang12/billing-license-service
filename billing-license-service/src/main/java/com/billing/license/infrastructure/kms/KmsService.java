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
