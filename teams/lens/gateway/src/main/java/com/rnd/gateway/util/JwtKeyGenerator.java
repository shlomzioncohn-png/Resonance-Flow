package com.rnd.gateway.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Generates RSA JWT key pair for local development.
 * Run this once to create jwt-private.pem and jwt-public.pem in project root.
 */
public class JwtKeyGenerator {

    public static void main(String[] args) throws NoSuchAlgorithmException, IOException {
        // Generate RSA 2048-bit key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        // Format private key as PEM
        String privateKeyPEM = formatPrivateKeyPEM(privateKey);
        String publicKeyPEM = formatPublicKeyPEM(publicKey);

        // Write to project root
        String projectRoot = "./";
        Files.write(Paths.get(projectRoot + "jwt-private.pem"), privateKeyPEM.getBytes());
        Files.write(Paths.get(projectRoot + "jwt-public.pem"), publicKeyPEM.getBytes());

        System.out.println("✅ JWT keys generated successfully:");
        System.out.println("   - jwt-private.pem");
        System.out.println("   - jwt-public.pem");
    }

    private static String formatPrivateKeyPEM(PrivateKey key) {
        String encoded = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PRIVATE KEY-----\n");
        for (int i = 0; i < encoded.length(); i += 64) {
            sb.append(encoded, i, Math.min(i + 64, encoded.length()));
            sb.append("\n");
        }
        sb.append("-----END PRIVATE KEY-----\n");
        return sb.toString();
    }

    private static String formatPublicKeyPEM(PublicKey key) {
        String encoded = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < encoded.length(); i += 64) {
            sb.append(encoded, i, Math.min(i + 64, encoded.length()));
            sb.append("\n");
        }
        sb.append("-----END PUBLIC KEY-----\n");
        return sb.toString();
    }
}
