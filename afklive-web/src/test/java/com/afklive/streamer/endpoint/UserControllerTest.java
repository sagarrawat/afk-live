package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.security.Principal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class UserControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
                        return webRequest.getUserPrincipal();
                    }
                })
                .build();
    }

    // Interface to combine UserDetails and Principal for mocking
    interface UserDetailsPrincipal extends UserDetails, Principal {}

    @Test
    public void testGetUserDetails() throws Exception {
        UserDetailsPrincipal mockUser = Mockito.mock(UserDetailsPrincipal.class);
        Mockito.when(mockUser.getUsername()).thenReturn("test@example.com");
        Mockito.when(mockUser.getName()).thenReturn("test@example.com"); // Principal method

        User user = new User();
        user.setUsername("test@example.com");
        user.setPlanType(PlanType.FREE);
        user.setFullName("Test User");

        Mockito.when(userService.getOrCreateUser("test@example.com")).thenReturn(user);

        mockMvc.perform(get("/api/user-details").principal(mockUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.name").value("Test User"));
    }

    @Test
    public void testGetUserInfo() throws Exception {
        UserDetailsPrincipal mockUser = Mockito.mock(UserDetailsPrincipal.class);
        Mockito.when(mockUser.getUsername()).thenReturn("test@example.com");
        Mockito.when(mockUser.getName()).thenReturn("test@example.com");

        User user = new User();
        user.setUsername("test@example.com");
        user.setPlanType(PlanType.FREE);
        user.setFullName("Test User");

        Mockito.when(userService.getOrCreateUser("test@example.com")).thenReturn(user);

        mockMvc.perform(get("/api/user-info").principal(mockUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }
}
