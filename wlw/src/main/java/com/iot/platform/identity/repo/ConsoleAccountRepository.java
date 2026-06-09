package com.iot.platform.identity.repo;

import com.iot.platform.identity.entity.ConsoleAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsoleAccountRepository extends JpaRepository<ConsoleAccount, Long> {

    Optional<ConsoleAccount> findByUsername(String username);
}
