package com.sky.account;

import com.sky.mapper.EmployeeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Convert existing plaintext/MD5 at startup; compare-and-set avoids overwriting concurrent password changes. */
@Component
@RequiredArgsConstructor
public class PasswordMigration implements ApplicationRunner {
    private final EmployeeMapper employees;
    private final EmployeePasswords passwords;
    @Override public void run(ApplicationArguments ignored) {
        for (var employee : employees.credentials()) {
            String migrated = passwords.migrate(employee.getPassword());
            if (!migrated.equals(employee.getPassword())) employees.changePassword(employee.getId(), employee.getPassword(), migrated);
        }
    }
}
