package com.example.cwmAi.Repository;

import com.example.cwmAi.dto.loginDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@ConditionalOnBean(loginMapper.class)
public class loginRepository {
    @Autowired
    loginMapper loginMapper;

    public String login(loginDTO loginDTO) {
        loginDTO found = loginMapper.findById(loginDTO);
        return found != null ? found.getPassword() : null;
    }

    public int signIn(loginDTO loginDTO) {
        return loginMapper.signIn(loginDTO);
    }

    public int checkId(String id) {
        return loginMapper.checkId(id);
    }

    public void updateLastLogin(String id, LocalDateTime lastLoginTime, String lastLoginIp) {
        loginMapper.updateLastLogin(id, lastLoginTime, lastLoginIp != null ? lastLoginIp : "");
    }
}
