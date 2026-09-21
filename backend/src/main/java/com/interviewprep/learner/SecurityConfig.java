package com.interviewprep.learner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter;

/**
 * Identity comes from Tailscale, not from a login form.
 *
 * <p>{@code tailscale serve} terminates TLS on the VM and adds {@code Tailscale-User-Login} naming the
 * person whose device sent the request. It also strips any copy of that header the client sent —
 * checked on the VM by capturing what reached the app while sending a forged one: the forged value
 * never arrived, and the real login did. The app listens only on 127.0.0.1, so serve is the only way
 * in; anything else would already need root on the VM.
 *
 * <p><b>CSRF still matters, and more than usual.</b> Because serve adds the identity to <em>every</em>
 * request from a learner's device, a malicious page open in the same browser could send a request to
 * this app and have it arrive as that learner. The SPA CSRF protection defeats that: a request that
 * changes anything must echo a token from a cookie, which another site's page cannot read.
 */
@Configuration
class SecurityConfig {

  static final String IDENTITY_HEADER = "Tailscale-User-Login";

  @Bean
  PreAuthenticatedAuthenticationProvider tailscaleAuthenticationProvider(LearnerDirectory directory) {
    PreAuthenticatedAuthenticationProvider provider = new PreAuthenticatedAuthenticationProvider();
    provider.setPreAuthenticatedUserDetailsService(directory);
    return provider;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, PreAuthenticatedAuthenticationProvider provider) throws Exception {
    RequestHeaderAuthenticationFilter tailscale = new RequestHeaderAuthenticationFilter();
    tailscale.setPrincipalRequestHeader(IDENTITY_HEADER);
    // A missing header is simply "not signed in"; the rules below turn that into a 403.
    tailscale.setExceptionIfHeaderMissing(false);
    tailscale.setAuthenticationManager(new ProviderManager(provider));

    return http
        .addFilterAt(tailscale, AbstractPreAuthenticatedProcessingFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/**").authenticated()
            // The SPA shell and its assets hold no data, and health must answer for monitoring.
            .anyRequest().permitAll())
        .csrf(csrf -> csrf.spa())
        // The identity arrives with every request, so there is nothing to keep in a session.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .build();
  }
}
