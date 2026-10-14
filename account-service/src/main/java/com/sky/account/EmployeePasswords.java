package com.sky.account;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

@Component
@RequiredArgsConstructor
public class EmployeePasswords {
    private final PasswordEncoder encoder;
    private static final String MD5_WRAPPED = "{argon2-md5}";
    public String encode(String raw) { return encoder.encode(raw); }
    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null) return false;
        if (stored.startsWith(MD5_WRAPPED)) return encoder.matches(md5(raw), stored.substring(MD5_WRAPPED.length()));
        if (stored.startsWith("$argon2")) return encoder.matches(raw, stored);
        // Compatibility during the first migration, never used for new password storage.
        if (stored.matches("[0-9a-fA-F]{32}")) return java.security.MessageDigest.isEqual(
                md5(raw).getBytes(StandardCharsets.UTF_8), stored.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return java.security.MessageDigest.isEqual(raw.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }
    public boolean current(String stored) { return stored != null && stored.startsWith("$argon2id$"); }
    public String migrate(String stored) {
        if (current(stored) || stored.startsWith(MD5_WRAPPED)) return stored;
        if (stored.matches("[0-9a-fA-F]{32}")) return MD5_WRAPPED + encoder.encode(stored.toLowerCase(java.util.Locale.ROOT));
        return encode(stored);
    }
    private String md5(String raw) { return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8)); }
}
