package slak.ckompiler.backend

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfiguration {
  @Bean
  fun corsConfigurationSource(@Value("\${ckompiler.allowed-origin:}") allowedOrigin: String): CorsConfigurationSource {
    val configuration = CorsConfiguration().apply {
      allowedOrigins = listOf(allowedOrigin).filter { it.isNotEmpty() }
      allowedMethods = listOf("GET", "POST", "PATCH", "DELETE", "HEAD", "OPTIONS")
      allowedHeaders = listOf("Authorization")
    }
    val source = UrlBasedCorsConfigurationSource()
    source.registerCorsConfiguration("/**", configuration)
    return source
  }

  @Bean
  fun filterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .authorizeHttpRequests()
        .anyRequest().authenticated().and()
        .csrf().disable()
        .cors().and()
        .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
        .oauth2ResourceServer { it.jwt() }
    return http.build()
  }
}
