package com.bulllazy.deaddrop.transfer;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wraps persisted transfer keys with one Android Keystore key. */
public final class SecureTransferKeyStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_ALIAS = "deaddrop_master_key";
    private static final String PREFS = "deaddrop_secure_keys";
    private static final String VALUE_PREFIX = "wrapped_";
    private static final int IV_LENGTH = 12;

    private SecureTransferKeyStore() { }

    public static void save(Context context, String name, byte[] key) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(key);
        byte[] stored = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, stored, 0, iv.length);
        System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);
        preferences(context).edit().putString(VALUE_PREFIX + name,
                Base64.encodeToString(stored, Base64.NO_WRAP)).commit();
    }

    public static byte[] load(Context context, String name) throws GeneralSecurityException {
        String encoded = preferences(context).getString(VALUE_PREFIX + name, null);
        if (encoded == null) {
            return null;
        }
        byte[] stored = Base64.decode(encoded, Base64.NO_WRAP);
        if (stored.length <= IV_LENGTH) {
            throw new GeneralSecurityException("Invalid wrapped key");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(stored, 0, iv, 0, iv.length);
        byte[] encrypted = new byte[stored.length - iv.length];
        System.arraycopy(stored, iv.length, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    public static void remove(Context context, String name) {
        preferences(context).edit().remove(VALUE_PREFIX + name).commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getMasterKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
            if (keyStore.containsAlias(MASTER_ALIAS)) {
                return ((SecretKey) keyStore.getKey(MASTER_ALIAS, null));
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(MASTER_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return generator.generateKey();
        } catch (Exception exception) {
            if (exception instanceof GeneralSecurityException) {
                throw (GeneralSecurityException) exception;
            }
            throw new GeneralSecurityException("Unable to access Android Keystore", exception);
        }
    }
}
