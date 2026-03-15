package com.example.cwmAi.Service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.cwmAi.Repository.loginRepository;
import com.example.cwmAi.Util.jwtUtil;
import com.example.cwmAi.dto.loginDTO;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class loginService {

    private final jwtUtil jwtUtil;
    private final Map<String, String> userStore = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private loginRepository loginRepository;

    public loginService(jwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    // config.txt 없거나 DB_URL 없을 때만 사용 (로컬 인메모리)
    @PostConstruct
    public void init() {
        userStore.put("admin", "admin"); // 기본 관리자 계정
    }

    /** IP는 DB 로그인 시 최근 로그인 기록용으로만 사용(선택). null 가능. */
    public String login(loginDTO loginDTO, String clientIp) {
        if (loginRepository != null) {
            String storedPw = loginRepository.login(loginDTO);
            if (storedPw != null && storedPw.equals(loginDTO.getPassword())) {
                loginRepository.updateLastLogin(loginDTO.getId(), LocalDateTime.now(), clientIp != null ? clientIp : "");  // last_login_time, last_login_ip 갱신
                return jwtUtil.createToken(loginDTO.getId());
            }
            return null;
        }
        String storedPw = userStore.get(loginDTO.getId());
        if (storedPw != null && storedPw.equals(loginDTO.getPassword())) {
            return jwtUtil.createToken(loginDTO.getId());
        }
        return null;
    }

    public String login(loginDTO loginDTO) {
        return login(loginDTO, null);
    }

    public String signIn(loginDTO loginDTO) {
        if (loginRepository != null) {
            if (loginRepository.checkId(loginDTO.getId()) > 0) return null;
            return loginRepository.signIn(loginDTO) > 0 ? jwtUtil.createToken(loginDTO.getId()) : null;
        }
        if (userStore.containsKey(loginDTO.getId())) return null;
        userStore.put(loginDTO.getId(), loginDTO.getPassword());
        return jwtUtil.createToken(loginDTO.getId());
    }

    public String checkId(String id) {
        if (loginRepository != null) {
            return loginRepository.checkId(id) > 0 ? "fail" : "success";
        }
        return userStore.containsKey(id) ? "fail" : "success";
    }
}

//package com.example.cwmAi.Service;
//
//import com.example.cwmAi.Repository.loginRepository;
//import com.example.cwmAi.Util.jwtUtil;
//import com.example.cwmAi.dto.loginDTO;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//@Service
//public class loginService {
//    @Autowired
//    private loginRepository loginRepository;
//    @Autowired
//    private jwtUtil jwtUtil;
//
//    public String login(loginDTO loginDTO) {
//        if (loginDTO.getPassword().equals(loginRepository.login(loginDTO))) {
//            String token = jwtUtil.createToken(loginDTO.getId());
//            return token;
//        } else {
//            return null;
//        }
//    }
//    public String signIn(loginDTO loginDTO){
//        if(loginRepository.signIn(loginDTO)>0){
//            String token = jwtUtil.createToken(loginDTO.getId());
//            return token;
//        }else{
//            return null;
//        }
//    }
//    public String checkId(String id){
//        if(loginRepository.checkId(id)<1){
//            return "success";
//        }else{
//            return "fail";
//        }
//    }
//}