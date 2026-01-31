package com.example.cwmAi.Service;

import org.springframework.stereotype.Service;

import com.example.cwmAi.Util.jwtUtil;
import com.example.cwmAi.dto.loginDTO;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class loginService {

    private final jwtUtil jwtUtil;
    private final Map<String, String> userStore = new ConcurrentHashMap<>();

    public loginService(jwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    // 애플리케이션 시작 시 기본 계정 등록
    @PostConstruct
    public void init() {
        userStore.put("admin", "admin"); // 기본 관리자 계정
    }
    //로그인
    public String login(loginDTO loginDTO) {
        String storedPw = userStore.get(loginDTO.getId());

        if (storedPw != null && storedPw.equals(loginDTO.getPassword())) {
            return jwtUtil.createToken(loginDTO.getId());
        }
        return null;
    }
    //회원가입
    public String signIn(loginDTO loginDTO) {
        // 이미 존재하는 ID면 실패
        if (userStore.containsKey(loginDTO.getId())) {
            return null;
        }

        userStore.put(loginDTO.getId(), loginDTO.getPassword());
        return jwtUtil.createToken(loginDTO.getId());
    }

    //ID 중복체크
    public String checkId(String id) {
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