package com.iot.platform.identity.service;

import com.iot.platform.identity.entity.ConsoleAccount;
import com.iot.platform.identity.repo.ConsoleAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class ConsoleUserDetailsService implements UserDetailsService {

    private final ConsoleAccountRepository accountRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        ConsoleAccount a = accountRepository.findByUsername(username.trim())
                .orElseThrow(() -> new UsernameNotFoundException(username));
        String role = a.getRole() != null && a.getRole().toUpperCase().startsWith("ADMIN") ? "ADMIN" : "USER";
        return new User(
                a.getUsername(),
                a.getPasswordHash(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
