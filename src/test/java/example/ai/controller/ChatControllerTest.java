package example.ai.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import example.ai.config.SecurityConfig;
import example.ai.model.ChatAnswer;
import example.ai.service.ChatInterface;

@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
class ChatControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    ChatInterface chatService;

    @MockBean
    JwtDecoder jwtDecoder;

    @Test
    void v1Chat_returnsAnswerWithSources() throws Exception {
        when(chatService.modelCall("What is the PO limit?", "alice", null)).thenReturn(new ChatAnswer("5000",
                List.of(new ChatAnswer.Source("procurement-policy", 2, "doc-id", "2026-11-04T10:00Z", "chunk-id", 0.21))));

        mvc.perform(post("/api/v1/chat").with(jwt().jwt(j -> j.subject("alice"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"What is the PO limit?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("5000"))
                .andExpect(jsonPath("$.sources[0].documentName").value("procurement-policy"))
                .andExpect(jsonPath("$.sources[0].documentVersion").value(2))
                .andExpect(jsonPath("$.sources[0].distance").value(0.21));
    }

    @Test
    void conversation_isScopedToTheTokenSubject() throws Exception {
        mvc.perform(post("/api/v1/chat").with(jwt().jwt(j -> j.subject("bob"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"hi\",\"conversationId\":\"c-1\"}"))
                .andExpect(status().isOk());

        verify(chatService).modelCall("hi", "bob", "c-1");
    }

    @Test
    void noToken_is401() throws Exception {
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void unversionedPath_isDenied_evenWithAValidToken() throws Exception {
        mvc.perform(post("/api/chat").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedBody_maps_to_400() throws Exception {
        mvc.perform(post("/api/v1/chat").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }
}
