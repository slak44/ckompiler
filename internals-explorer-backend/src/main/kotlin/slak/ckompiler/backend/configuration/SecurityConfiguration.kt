package slak.ckompiler.backend.configuration

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import slak.ckompiler.backend.configuration.WebSocketConfiguration.Companion.WEBSOCKET_ENDPOINT

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {
  @Bean
  fun corsConfigurationSource(@Value("\${ckompiler.allowed-origins:}") allowedOriginList: List<String>): CorsConfigurationSource {
    val configuration = CorsConfiguration().apply {
      allowedOrigins = allowedOriginList.filter { it.isNotEmpty() }
      allowedMethods = listOf("GET", "POST", "PATCH", "DELETE", "HEAD", "OPTIONS")
      allowedHeaders = listOf("Authorization", "Content-Type")
    }
    val source = UrlBasedCorsConfigurationSource()
    source.registerCorsConfiguration("/**", configuration)
    return source
  }

  @Bean
  fun filterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .authorizeHttpRequests()
        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/viewstate/list").authenticated()
        .requestMatchers(HttpMethod.GET, "/api/viewstate/**").permitAll()
        .requestMatchers(HttpMethod.GET, WEBSOCKET_ENDPOINT).permitAll()
        .anyRequest().authenticated().and()
        .csrf().disable()
        .cors().and()
        .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
        .oauth2ResourceServer { it.jwt() }
    return http.build()
  }
}
