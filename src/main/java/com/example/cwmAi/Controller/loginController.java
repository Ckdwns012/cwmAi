package com.example.cwmAi.Controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
            model.addAttribute("message","sign in success");
            return "redirect:/aiChatPage";
        }else{
            model.addAttribute("message","login fail");
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
            model.addAttribute("message","sign in success");
            return "redirect:/aiChatPage";
        }else{
            model.addAttribute("message","sign in fail");
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
}
