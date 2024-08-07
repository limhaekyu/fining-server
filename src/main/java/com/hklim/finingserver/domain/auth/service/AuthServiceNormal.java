package com.hklim.finingserver.domain.auth.service;

import com.hklim.finingserver.domain.auth.dto.*;
import com.hklim.finingserver.domain.member.entity.Member;
import com.hklim.finingserver.domain.member.entity.RoleType;
import com.hklim.finingserver.domain.member.repository.MemberRepository;
import com.hklim.finingserver.domain.member.service.MemberService;
import com.hklim.finingserver.domain.portfolio.service.PortfolioService;
import com.hklim.finingserver.global.entity.RedisKeyType;
import com.hklim.finingserver.global.exception.ApplicationErrorException;
import com.hklim.finingserver.global.exception.ApplicationErrorType;
import com.hklim.finingserver.global.utils.*;
import io.lettuce.core.RedisException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.regex.Pattern;


@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceNormal implements AuthService {

    @Value("${auth.pw.pattern}")
    String pwPattern;
    @Value("${auth.pw.temp.length}")
    int tempPwLength;
    @Value("${auth.jwt.refresh_expiration_time}")
    String refreshTokenExpTime;
    @Value("${spring.data.redis.expiration_time.logout}")
    String logoutExpTime;

    private static final char[] randomAllCharacters = new char[]{
            //number
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            //uppercase
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            //lowercase
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            //special symbols
            '@', '$', '!', '%', '*', '?', '&'
    };

    private final MemberRepository memberRepository;
    private final JwtUtils jwtUtils;
    private final AuthUtils authUtils;
    private final RedisUtils redisUtils;
    private final CookieUtils cookieUtils;
    private final MemberService memberService;
    private final PortfolioService portfolioService;
    private final VerifyUtils verifyUtils;

    @Override
    @Transactional
    public Long signup(SignupRequestDto signupInfo) {
        log.info("[SIGNUP PROCESS] START");
        verifySignupInfo(signupInfo.getEmail(), signupInfo.getPhoneNumber(), signupInfo.getPassword());
        chkSignupValidation(signupInfo.getEmail());
        SignupRequestDto saveInfo = SignupRequestDto.builder()
                .email(signupInfo.getEmail())
                .password(authUtils.encPassword(signupInfo.getPassword()))
                .name(signupInfo.getName())
                .phoneNumber(signupInfo.getPhoneNumber())
                .build();
        Long res = memberRepository.save(saveInfo.toEntity()).getId();
        log.info("[SIGNUP PROCESS] END");
        return res;
    }

    @Override
    @Transactional
    public AccessTokenResponseDto login(LoginRequestDto loginInfo, HttpServletResponse response) {
        String email = loginInfo.getEmail();
        String password = loginInfo.getPassword();
        Member member = memberRepository.findByEmail(email).orElseThrow(()->
                new ApplicationErrorException(ApplicationErrorType.NOT_FOUND_MEMBER));
        log.info("[NORMAL-LOGIN] Find Member email : {}", member.getEmail());

        authUtils.checkPassword(member.getPassword(), password);

        JwtUserInfo userInfo = new JwtUserInfo();
        userInfo.toDto(member);

        log.info("[NORMAL-LOGIN] Create Access token. ");
        String accessToken = jwtUtils.createAccessToken(userInfo);
        log.info("[NORMAL-LOGIN] Create Refresh token. ");
        String refreshToken = jwtUtils.createRefreshToken(userInfo);

        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setMaxAge(Integer.parseInt(refreshTokenExpTime));
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setPath("/");

        response.addCookie(cookie);

        try {
            redisUtils.setDataExpire(RedisKeyType.REFRESH_TOKEN.getSeparator() + member.getEmail(), refreshToken, Long.parseLong(refreshTokenExpTime)/1000);
        } catch (Exception e) {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_TO_SAVE_DATA, e);
        }

        return AccessTokenResponseDto.builder()
                .accessToken(accessToken)
                .build();
    }

    @Override
    public void logout(String authorizationHeader, HttpServletResponse response) {
        String accessToken = authorizationHeader.substring(7);
        log.info("[LOGOUT PROCESS] Set accessToken to logout token list, START");
        try {
            redisUtils.setDataExpire(RedisKeyType.LOGOUT_TOKEN.getSeparator()+accessToken, String.valueOf(true), Long.parseLong(logoutExpTime));
        } catch (RedisException e) {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_JWT_LOGOUT, "[LOGOUT PROCESS] Logout failed. Please try again.");
        } finally {
            cookieUtils.removeCookie("refresh_token", response);
        }
        log.info("[LOGOUT PROCESS] Set accessToken to logout token list, END");
    }

    @Override
    public AccessTokenResponseDto reissueAccessToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        log.info("[REISSUE-TOKEN] Reissue Access Token Process Start. ");
        String refreshToken = "";
        String accessToken = "";
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            accessToken = authorizationHeader.substring(7);
        } else {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_JWT_REISSUE, "[REISSUE-TOKEN] Access Token is Empty. ");
        }

        String userEmail = jwtUtils.getEmail(accessToken);
        Long userId = jwtUtils.getUserId(accessToken);
        RoleType role = RoleType.valueOf(jwtUtils.getRole(accessToken));

        if (cookies == null) {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_JWT_REISSUE, "[REISSUE-TOKEN] Refresh Token is Empty in cookie. Please login again. ");
        }
        refreshToken = cookieUtils.getValue(cookies, "refresh_token");

        log.info("[REISSUE-TOKEN] Refresh token validate. ");
        if (!jwtUtils.validateToken(refreshToken)) {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_JWT_REISSUE, "[REISSUE-TOKEN] Refresh Token is Expired. Please login again. ");
        }

        log.info("[REISSUE-TOKEN] Refresh Token compare with saved Data");
        String savedRefreshToken = redisUtils.getData(RedisKeyType.REFRESH_TOKEN.getSeparator()+userEmail);
        if (savedRefreshToken == null) {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_JWT_REISSUE, "[REISSUE-TOKEN] Saved Refresh token is empty. Please login again. ");
        }
        if (savedRefreshToken.equals(refreshToken)) {
            JwtUserInfo userInfo = JwtUserInfo.builder()
                    .memberId(userId)
                    .email(userEmail)
                    .role(role)
                    .build();
            log.info("[REISSUE-TOKEN] Access token reissue. ");
            String newAccessToken = jwtUtils.createAccessToken(userInfo);
            return AccessTokenResponseDto.builder()
                    .accessToken(newAccessToken)
                    .build();
        } else {
            log.info("[REISSUE-TOKEN] Refresh token is not matching. Refresh token reset.");
            redisUtils.deleteData(RedisKeyType.REFRESH_TOKEN.getSeparator()+userEmail);
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_JWT_REISSUE, "[REISSUE-TOKEN] Refresh Token does not match. Please login again. ");
        }
    }


    @Override
    @Transactional
    public void withdrawalMember(UserDetails user, HttpServletRequest request, HttpServletResponse response) {
        String accessToken = "";
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            accessToken = authorizationHeader.substring(7);
        } else {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_WITHDRAWAL_MEMBER, "[WITHDRAWAL-MEMBER] Access Token is Empty. ");
        }

        try {
            redisUtils.setDataExpire(RedisKeyType.LOGOUT_TOKEN.getSeparator()+accessToken, String.valueOf(true), Long.parseLong(logoutExpTime));
        } catch (RedisException e) {
            throw new ApplicationErrorException(ApplicationErrorType.FAIL_WITHDRAWAL_MEMBER, "[WITHDRAWAL-MEMBER] Fail to resist redis, Unavailable token. Please try again.");
        } finally {
            cookieUtils.removeCookie("refresh_token", response);
        }

        Member member = memberService.findMemberById(Long.valueOf(user.getUsername()));
        member.withdrawal();
        portfolioService.withdrawalMember(member);
        memberService.saveMember(member);
    }


    private void chkSignupValidation(String email) {
        log.info("[SIGNUP-PROCESS] Check Email Validation ");
        if (memberRepository.existsByEmail(email)) {
            throw new ApplicationErrorException(ApplicationErrorType.DATA_DUPLICATED_ERROR,
                    "[SIGNUP-PROCESS] Email is Duplicated, Please check again.");
        }
        log.info("[SIGNUP-PROCESS] Validation Check, SUCCESS! ");
    }

    private void verifySignupInfo(String email, String phoneNumber, String password) {
        verifyUtils.isAvailableEmailFormat(email);
        verifyUtils.isAvailablePhoneNumberFormat(phoneNumber);
        verifyUtils.isAvailablePasswordFormat(password);
    }

    public InquiryEmailResponseDto inquiryEmail(InquiryEmailRequestDto inquiryEmailInfo) {
        // 이름, 핸드폰번호로 조회
        String name = inquiryEmailInfo.getName();
        String phoneNumber = inquiryEmailInfo.getPhoneNumber();
        // 데이터 검증필요, 이름 형식, 핸드폰번호 형식
        Member member = memberRepository.findByNameAndPhoneNumber(name, phoneNumber).orElseThrow(
                () -> new ApplicationErrorException(ApplicationErrorType.INTERNAL_ERROR));
        if (member == null) {
            throw new ApplicationErrorException(ApplicationErrorType.NOT_FOUND_MEMBER);
        } else {
            InquiryEmailResponseDto res = new InquiryEmailResponseDto();
            res.toDto(member);
            return res;
        }
    }

    public InquiryPwResponseDto inquiryPw(InquiryPwRequestDto inquiryPwInfo) {
        String email = inquiryPwInfo.getEmail();
        String name = inquiryPwInfo.getName();
        String phoneNumber = inquiryPwInfo.getPhoneNumber();
        log.info("{}, {}, {}", email, name, phoneNumber);
        Member member = memberRepository.findByEmailAndNameAndPhoneNumber(email, name, phoneNumber).orElseThrow(
                () -> new ApplicationErrorException(ApplicationErrorType.NOT_FOUND_MEMBER));

        String tempPw = createTemporaryPw(tempPwLength);
        String encTempPw = authUtils.encPassword(tempPw);
        member.updatePw(encTempPw);
        memberRepository.save(member);
        InquiryPwResponseDto res = new InquiryPwResponseDto(tempPw);
        return res;
    }

    private String createTemporaryPw(int tempPwLength) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();

        int randomAllCharactersLength = randomAllCharacters.length;
        for (int i=0; i<tempPwLength; i++) {
            sb.append(randomAllCharacters[random.nextInt(randomAllCharactersLength)]);
        }
        String tempPw = sb.toString();
        if (!Pattern.matches(pwPattern, tempPw)) {
            return createTemporaryPw(tempPwLength);
        }
        return tempPw;
    }
}
