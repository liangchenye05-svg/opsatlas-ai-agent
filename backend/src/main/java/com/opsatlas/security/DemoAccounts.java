package com.opsatlas.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoAccounts implements ApplicationRunner {
    private final JdbcTemplate database;
    private final PasswordEncoder passwords;
    private final String password;

    public DemoAccounts(JdbcTemplate database, PasswordEncoder passwords,
                        @Value("${opsatlas.demo-password}") String password) {
        this.database = database;
        this.passwords = passwords;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!password.isBlank()) {
            database.update("UPDATE account SET password_hash = ? WHERE password_hash = '!'",
                    passwords.encode(password));
        }
    }
}
