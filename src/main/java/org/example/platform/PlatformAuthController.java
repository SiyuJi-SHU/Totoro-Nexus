package org.example.platform;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
public class PlatformAuthController {
    private final PlatformIdentity identity;private final JdbcTemplate db;private final PasswordEncoder passwords;
    public PlatformAuthController(PlatformIdentity identity,JdbcTemplate db,PasswordEncoder passwords){this.identity=identity;this.db=db;this.passwords=passwords;}
    @GetMapping("/api/auth/me") public Object me(Authentication user) {
        if(user==null||!user.isAuthenticated()||"anonymousUser".equals(user.getName()))return Map.of("authenticated",false);
        return Map.of("authenticated",true,"id",identity.userId(user),"username",user.getName(),"role",identity.admin(user)?"ADMIN":"MEMBER");
    }
    @GetMapping("/api/auth/csrf") public Object csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    public record ChangePassword(String currentPassword,String newPassword){}
    @PostMapping("/api/auth/password") public Object changePassword(Authentication user,@RequestBody ChangePassword input) {
        String id=identity.userId(user);String hash=db.queryForObject("SELECT password_hash FROM platform_users WHERE id=?",String.class,id);
        if(input.currentPassword()==null||!passwords.matches(input.currentPassword(),hash))throw PlatformCatalog.bad("原密码不正确");
        String next=password(input.newPassword());db.update("UPDATE platform_users SET password_hash=? WHERE id=?",passwords.encode(next),id);return Map.of("changed",true);
    }
    public record NewUser(String username,String password){}
    @GetMapping("/api/platform/admin/users") public Object users(){return db.queryForList("SELECT id,username,role,enabled,created_at FROM platform_users ORDER BY created_at");}
    @PostMapping("/api/platform/admin/users") public Object create(@RequestBody NewUser input) {
        String username=PlatformCatalog.required(input.username(),120,"用户名");
        if(!username.matches("[A-Za-z0-9_.-]{3,80}"))throw PlatformCatalog.bad("用户名须为3–80位字母、数字或_.-");
        String id=UUID.randomUUID().toString();db.update("INSERT INTO platform_users(id,username,password_hash,role) VALUES(?,?,?,'MEMBER')",id,username,passwords.encode(password(input.password())));
        return Map.of("id",id,"username",username,"role","MEMBER");
    }
    private String password(String value){if(value==null||value.length()<12||value.length()>100)throw PlatformCatalog.bad("密码长度须为12–100位");return value;}
}
