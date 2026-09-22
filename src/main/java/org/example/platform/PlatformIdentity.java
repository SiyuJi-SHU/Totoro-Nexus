package org.example.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Service
public class PlatformIdentity {
    private final JdbcTemplate db;
    public PlatformIdentity(JdbcTemplate db){this.db=db;}
    public String userId(Authentication user) {
        if(user==null||!user.isAuthenticated()||"anonymousUser".equals(user.getName()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        var values=db.queryForList("SELECT id FROM platform_users WHERE username=? AND enabled=TRUE",String.class,user.getName());
        if(values.isEmpty())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号已停用");return values.get(0);
    }
    public boolean admin(Authentication user){return user!=null&&user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));}
}
