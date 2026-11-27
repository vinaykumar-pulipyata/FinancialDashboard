package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.model.AppSetting;
import com.learning.spring.financialdashboard.repository.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The key to the Daily Calendar Notes. The notes are sealed with a random data key (AES-256-GCM); that key is kept in the
 * database only wrapped by a key made from the dashboard password (PBKDF2), so the database file and its backups cannot be
 * read without the password. The password reaches the server with every request (HTTP Basic): {@code DiaryKeyFilter} binds it
 * to the request's thread, and {@link #key()} unwraps the data key with it. Changing the password re-wraps the same data key,
 * so the notes stay readable. After a forgotten-password reset the old wrapped key no longer opens with a new password: the
 * notes stay sealed (never deleted) and open again if the old password is set once more.
 *
 * <p>The password and the keys are never logged or stored in the clear.
 */
@Service
@Transactional
public class DiaryVault {

    private static final Logger log = LoggerFactory.getLogger(DiaryVault.class);

    static final String SALT = "diary.salt";
    static final String WRAPPED = "diary.key";
    private static final int ITERATIONS = 120_000;
    private static final int IV_BYTES = 12;
    private static final ThreadLocal<String> BOUND = new ThreadLocal<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppSettingRepository settings;
    /** Per password (by its SHA-256): the data key it opens, or empty when it opens nothing. Derivation is slow, so once per password. */
    private final Map<String, Optional<SecretKey>> cache = new ConcurrentHashMap<>();

    public DiaryVault(AppSettingRepository settings) {
        this.settings = settings;
    }

    /** The password of the request being served; cleared by {@link #unbind()} when it ends. */
    public static void bind(String password) {
        BOUND.set(password);
    }

    /** Forgets the opened keys (they are opened again from the password on the next request). */
    public void forget() {
        cache.clear();
    }

    /** True when the request being served carried a login. */
    public static boolean hasLogin() {
        return BOUND.get() != null;
    }

    public static void unbind() {
        BOUND.remove();
    }

    /** The data key, or empty when this request carries no password or the password does not open the diary. */
    public Optional<SecretKey> key() {
        String password = BOUND.get();
        if (password == null || password.isEmpty()) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(fingerprint(password), k -> open(password, true));
    }

    /** True when the diary has been sealed with some password (a wrapped key exists). */
    @Transactional(readOnly = true)
    public boolean exists() {
        return settings.existsById(WRAPPED);
    }

    /** A password has just been set: creates the data key if there is none yet. An older key that this password does not open stays as it is. */
    public void passwordSet(String password) {
        cache.clear();
        open(password, true);
    }

    /** The password changes: the same data key is wrapped again by the new one, so every sealed note stays readable. */
    public void passwordChanged(String current, String next) {
        Optional<SecretKey> key = open(current, false);
        cache.clear();
        if (key.isPresent()) {
            store(key.get(), next);
            log.info("The diary key was re-wrapped for the new password");
        }
    }

    /** Opens (or, when asked, first creates) the data key with a password. */
    private Optional<SecretKey> open(String password, boolean create) {
        Optional<AppSetting> wrapped = settings.findById(WRAPPED);
        if (wrapped.isEmpty()) {
            if (!create) {
                return Optional.empty();
            }
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            SecretKey key = new SecretKeySpec(raw, "AES");
            store(key, password);
            log.info("A diary key was created");
            return Optional.of(key);
        }
        try {
            byte[] salt = Base64.getDecoder().decode(settings.findById(SALT).orElseThrow().getValue());
            byte[] raw = transform(Cipher.DECRYPT_MODE, derive(password, salt), Base64.getDecoder().decode(wrapped.get().getValue()));
            return Optional.of(new SecretKeySpec(raw, "AES"));
        } catch (GeneralSecurityException | RuntimeException e) {
            return Optional.empty();   // not this password's key
        }
    }

    private void store(SecretKey key, String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String wrapped = Base64.getEncoder().encodeToString(transform(Cipher.ENCRYPT_MODE, derive(password, salt), key.getEncoded()));
            put(SALT, Base64.getEncoder().encodeToString(salt));
            put(WRAPPED, wrapped);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not wrap the diary key", e);
        }
    }

    private void put(String name, String value) {
        AppSetting setting = settings.findById(name).orElse(null);
        if (setting == null) {
            settings.save(new AppSetting(name, value));
        } else {
            setting.setValue(value);
            settings.save(setting);
        }
    }

    /** Seals a text: a fresh random IV in front of the AES-GCM output, as base64. */
    public String seal(String plain, SecretKey key) {
        try {
            return Base64.getEncoder().encodeToString(transform(Cipher.ENCRYPT_MODE, key, plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal a note", e);
        }
    }

    /** The text inside a sealed value, or empty when this key does not open it. */
    public Optional<String> unseal(String sealed, SecretKey key) {
        try {
            return Optional.of(new String(transform(Cipher.DECRYPT_MODE, key, Base64.getDecoder().decode(sealed)), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Encrypts (IV first in the result) or decrypts (IV first in the input). */
    private static byte[] transform(int mode, SecretKey key, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        if (mode == Cipher.ENCRYPT_MODE) {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            cipher.init(mode, key, new GCMParameterSpec(128, iv));
            byte[] out = cipher.doFinal(input);
            byte[] all = new byte[IV_BYTES + out.length];
            System.arraycopy(iv, 0, all, 0, IV_BYTES);
            System.arraycopy(out, 0, all, IV_BYTES, out.length);
            return all;
        }
        cipher.init(mode, key, new GCMParameterSpec(128, input, 0, IV_BYTES));
        return cipher.doFinal(input, IV_BYTES, input.length - IV_BYTES);
    }

    private static SecretKey derive(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
    }

    private static String fingerprint(String password) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
