package com.iot.platform.identity.bootstrap;

import com.iot.platform.identity.entity.ConsoleAccount;
import com.iot.platform.identity.repo.ConsoleAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动写入默认账号（仅当表为空）：admin / admin123，user / user123。
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class ConsoleAccountDataLoader implements CommandLineRunner {

    private final ConsoleAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (accountRepository.count() > 0) {
            return;
        }
        ConsoleAccount admin = new ConsoleAccount();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode("admin123"));
        admin.setRole("ADMIN");
        accountRepository.save(admin);

        ConsoleAccount user = new ConsoleAccount();
        user.setUsername("user");
        user.setPasswordHash(passwordEncoder.encode("user123"));
        user.setRole("USER");
        accountRepository.save(user);
    }
}
