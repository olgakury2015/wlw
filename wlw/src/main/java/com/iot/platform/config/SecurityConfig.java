package com.iot.platform.config;

import com.iot.platform.identity.service.ConsoleUserDetailsService;
import com.iot.platform.security.ApiKeyAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final ConsoleUserDetailsService consoleUserDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(consoleUserDetailsService).passwordEncoder(passwordEncoder);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .csrf()
                .ignoringAntMatchers("/api/**", "/h2-console/**")
                .and()
                .headers()
                .frameOptions()
                .sameOrigin()
                .and()
                .authorizeRequests()
                .antMatchers("/h2-console/**").hasRole("ADMIN")
                .antMatchers("/css/**", "/login", "/error").permitAll()
                .antMatchers(HttpMethod.POST, "/api/v1/telemetry", "/api/v1/http/ingest").permitAll()
                .antMatchers(HttpMethod.POST, "/api/v1/modbus/**").permitAll()
                .antMatchers(HttpMethod.POST, "/api/v1/mqtt/**").permitAll()
                .antMatchers("/api/v1/health").permitAll()
                .antMatchers("/api/v1/nodered/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/telemetry/recent").hasAnyRole("ADMIN", "USER", "API_CLIENT")
                .antMatchers("/api/v1/management/**").hasRole("ADMIN")
                .antMatchers("/api/**").authenticated()
                .anyRequest().authenticated()
                .and()
                .formLogin()
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll()
                .and()
                .logout()
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll();
    }
}
