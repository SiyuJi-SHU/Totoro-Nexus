package org.example.platform;

import org.example.service.KnowledgeFiles;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Configuration
public class PlatformSecurity {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
    @Bean UserDetailsService userDetailsService(JdbcTemplate db) {
        return username->db.query("SELECT username,password_hash,role,enabled FROM platform_users WHERE username=?",(r,n)->
                User.withUsername(r.getString(1)).password(r.getString(2)).roles(r.getString(3)).disabled(!r.getBoolean(4)).build(),username)
                .stream().findFirst().orElseThrow(()->new UsernameNotFoundException("用户不存在"));
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(c->c.disable())
                .csrf(c->c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(a->a
                        .requestMatchers("/api/auth/me","/api/auth/csrf","/api/auth/login","/actuator/health","/error").permitAll()
                        .requestMatchers("/api/console/**","/api/platform/admin/**","/api/upload","/api/chat/rag-eval","/api/scenarios/select","/milvus/**","/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(f->f.loginPage("/login.html").loginProcessingUrl("/api/auth/login")
                        .successHandler((q,r,a)->{r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"authenticated\":true}");})
                        .failureHandler((q,r,e)->{r.setStatus(401);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"message\":\"账号或密码错误\"}");}))
                .logout(l->l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q,r,a)->r.setStatus(204)))
                .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->{r.setStatus(401);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"message\":\"请先登录\"}");})
                        .accessDeniedHandler((q,r,x)->{r.setStatus(403);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"message\":\"当前操作没有权限或请求令牌已过期\"}");}));
        return http.build();
    }
    @Component
    @Order(1)
    static class InitialAdmin implements ApplicationRunner {
        private final JdbcTemplate db;private final PasswordEncoder passwords;private final KnowledgeFiles files;
        InitialAdmin(JdbcTemplate db,PasswordEncoder passwords,KnowledgeFiles files){this.db=db;this.passwords=passwords;this.files=files;}
        @Override public void run(ApplicationArguments args) throws Exception {
            if(db.queryForObject("SELECT COUNT(*) FROM platform_users",Integer.class)>0)return;
            Path secret=files.root().resolve(".platform").resolve("admin-initial-password.txt");
            if(Files.isSymbolicLink(secret)||Files.isSymbolicLink(secret.getParent()))throw new IllegalStateException("初始化路径无效");
            String password=System.getenv("PLATFORM_ADMIN_PASSWORD");
            if(password==null||password.isBlank()) {
                if(Files.isRegularFile(secret))password=Files.readString(secret,StandardCharsets.UTF_8).strip();
                else {byte[] random=new byte[24];new SecureRandom().nextBytes(random);password=Base64.getUrlEncoder().withoutPadding().encodeToString(random);KnowledgeFiles.atomicWrite(secret,password.getBytes(StandardCharsets.UTF_8));
                    if(Files.getFileStore(secret).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
            }
            if(password.length()<12)throw new IllegalStateException("初始管理员密码至少12位");
            db.update("INSERT INTO platform_users(id,username,password_hash,role) VALUES(?,?,?,'ADMIN')",UUID.randomUUID().toString(),"admin",passwords.encode(password));
        }
    }
}
