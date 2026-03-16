package com.example.cwmAi.Repository;

import com.example.cwmAi.dto.loginDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface loginMapper {
    loginDTO findById(loginDTO loginDTO);
    int signIn(loginDTO loginDTO);
    int checkId(String id);
    int updateLastLogin(@Param("id") String id, @Param("lastLoginTime") LocalDateTime lastLoginTime, @Param("lastLoginIp") String lastLoginIp);
}
