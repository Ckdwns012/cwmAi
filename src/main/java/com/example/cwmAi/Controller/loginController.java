package com.example.cwmAi.Controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.example.cwmAi.Service.loginService;
import com.example.cwmAi.dto.loginDTO;

@Controller
public class loginController {
    @Autowired
    private loginService loginService;

//    로그인
    @RequestMapping("loginPage")
    public String loginPage(Model model){
        model.addAttribute("loginDTO", new loginDTO());
        return "loginPage";
    }
    @RequestMapping("login")
    public String login(@ModelAttribute loginDTO loginDTO, Model model, HttpServletResponse response){
        String token = loginService.login(loginDTO);

        if (token != null) {
            Cookie cookie = new Cookie("accessToken", token);
            cookie.setHttpOnly(true); // XSS 방지
            cookie.setPath("/");
            response.addCookie(cookie);
            return "redirect:/aiChatPage";
        }else{
            model.addAttribute("message","아이디 또는 비밀번호가 올바르지 않습니다.");
            model.addAttribute("loginDTO", loginDTO);
            return "loginPage";
        }
    }
//    회원가입
    @RequestMapping("signUp")
    public String signUp(){
        return "signUpPage";
    }
    @RequestMapping("signIn")
    public String signIn(@ModelAttribute loginDTO loginDTO, Model model, HttpServletResponse response){

        String token = loginService.signIn(loginDTO);

        if (token != null) {
            Cookie cookie = new Cookie("accessToken", token);
            cookie.setHttpOnly(true); // XSS 방지
            cookie.setPath("/");
            response.addCookie(cookie);
            return "redirect:/aiChatPage";
        }else{
            model.addAttribute("message","이미 사용 중인 아이디입니다.");
            return "signUpPage";
        }
    }
    @RequestMapping("checkId")
    @ResponseBody
    public String checkId(@RequestParam("id") String id){
        return loginService.checkId(id);
    }

    @RequestMapping("/aiChatPage")
    public String aiChatPage(Model model, jakarta.servlet.http.HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        model.addAttribute("userId", userId != null ? userId : "");
        model.addAttribute("isAdmin", "admin".equals(userId));
        return "aiChatPage";
    }
    
    // 로그아웃
    @RequestMapping("/logout")
    public String logout(HttpServletResponse response) {
        // 쿠키 삭제
        Cookie cookie = new Cookie("accessToken", null);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0); // 쿠키 만료
        response.addCookie(cookie);
        return "redirect:/loginPage";
    }

    private void setAccessTokenCookie(HttpServletResponse response, String token) {
        // 내부망이어도 HTTPS면 secure=true 권장
        boolean secure = false; // HTTPS 적용 시 true로 바꿔야함.

        ResponseCookie cookie = ResponseCookie.from("accessToken", token)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(60 * 60) // 1시간
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearAccessTokenCookie(HttpServletResponse response) {
        boolean secure = false; // HTTPS 적용 시 true로 바꾸세요.

        ResponseCookie cookie = ResponseCookie.from("accessToken", "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(0)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
