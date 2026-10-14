package com.sky.account;

import com.sky.security.ApiSecurityConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmployeePasswordTest {
    private final EmployeePasswords passwords = new EmployeePasswords(new ApiSecurityConfiguration().passwordEncoder());
    @Test void existingPlaintextAndMd5CanMigrateWithoutBreakingLogin() {
        String plain = passwords.migrate("123456");
        String wrapped = passwords.migrate("e10adc3949ba59abbe56e057f20f883e");
        assertTrue(plain.startsWith("$argon2id$")); assertTrue(wrapped.startsWith("{argon2-md5}$argon2id$"));
        assertTrue(passwords.matches("123456", plain)); assertTrue(passwords.matches("123456", wrapped));
        assertFalse(passwords.matches("bad", plain)); assertFalse(passwords.matches("bad", wrapped));
        assertFalse(passwords.matches("e10adc3949ba59abbe56e057f20f883e", wrapped));
        assertEquals(plain, passwords.migrate(plain)); assertEquals(wrapped, passwords.migrate(wrapped));
    }
    @Test void newPasswordsHaveRandomSaltAndNeverStoreRawValue() {
        String first = passwords.encode("secure-new-password"), second = passwords.encode("secure-new-password");
        assertNotEquals(first, second); assertTrue(passwords.matches("secure-new-password", first));
    }
}
