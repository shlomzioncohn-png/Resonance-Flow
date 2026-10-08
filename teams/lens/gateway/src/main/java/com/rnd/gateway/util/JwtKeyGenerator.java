package com.rnd.gateway.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Utility to generate JWT RSA key pair for local development.
 * 
 * Usage: Run this main method to generate jwt-private.pem and jwt-public.pem
 * in the current directory (or specify output path).
 * 
 * Generated keys are in PKCS8 (private) and X509 (public) PEM format.
 */
public class JwtKeyGenerator {

    private static final int KEY_SIZE = 2048;
    private static final String ALGORITHM = "RSA";
    
    public static void main(String[] args) throws Exception {
        String outputDir = args.length > 0 ? args[0] : ".";
        
        System.out.println("Generating " + KEY_SIZE + "-bit RSA key pair...");
        KeyPair keyPair = generateKeyPair();
        
        Path privateKeyPath = Paths.get(outputDir, "jwt-private.pem");
        Path publicKeyPath = Paths.get(outputDir, "jwt-public.pem");
        
        savePrivateKey(keyPair.getPrivate(), privateKeyPath);
        savePublicKey(keyPair.getPublic(), publicKeyPath);
        
        System.out.println("✓ Private key saved to: " + privateKeyPath.toAbsolutePath());
        System.out.println("✓ Public key saved to: " + publicKeyPath.toAbsolutePath());
        System.out.println("\nKeys are ready for use with LENS Gateway.");
        System.out.println("Update .env with:");
        System.out.println("  LENS_JWT_PRIVATE_KEY_LOCATION=" + privateKeyPath);
        System.out.println("  LENS_JWT_PUBLIC_KEY_LOCATION=" + publicKeyPath);
    }

    /**
     * Generate a 2048-bit RSA key pair.
     */
    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM);
        keyGen.initialize(KEY_SIZE);
        return keyGen.generateKeyPair();
    }

    /**
     * Save private key to file in PKCS8 PEM format.
     */
    private static void savePrivateKey(PrivateKey privateKey, Path path) throws IOException {
        byte[] privateKeyEncoded = privateKey.getEncoded();
        String base64Encoded = Base64.getEncoder().encodeToString(privateKeyEncoded);
        
        // Format as PEM (PKCS8)
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN PRIVATE KEY-----\n");
        
        // Split into 64-char lines
        for (int i = 0; i < base64Encoded.length(); i += 64) {
            int end = Math.min(i + 64, base64Encoded.length());
            pem.append(base64Encoded, i, end).append("\n");
        }
        
        pem.append("-----END PRIVATE KEY-----\n");
        
        Files.write(path, pem.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Save public key to file in X509 PEM format.
     */
    private static void savePublicKey(PublicKey publicKey, Path path) throws IOException {
        byte[] publicKeyEncoded = publicKey.getEncoded();
        String base64Encoded = Base64.getEncoder().encodeToString(publicKeyEncoded);
        
        // Format as PEM (X509)
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN PUBLIC KEY-----\n");
        
        // Split into 64-char lines
        for (int i = 0; i < base64Encoded.length(); i += 64) {
            int end = Math.min(i + 64, base64Encoded.length());
            pem.append(base64Encoded, i, end).append("\n");
        }
        
        pem.append("-----END PUBLIC KEY-----\n");
        
        Files.write(path, pem.toString().getBytes(StandardCharsets.UTF_8));
    }
}
