package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.port.out.MediaProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaControllerTest {

    private static ObjectProvider<MediaProvider> provider(MediaProvider mediaProvider) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MediaProvider> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(mediaProvider);
        return p;
    }

    @Test
    void ttsReturnsAudioWhenAvailable() throws Exception {
        MediaProvider mediaProvider = mock(MediaProvider.class);
        when(mediaProvider.isAvailable()).thenReturn(true);
        when(mediaProvider.textToSpeech("Hallo", null, null)).thenReturn(new byte[] {1, 2, 3});
        when(mediaProvider.providerName()).thenReturn("test");

        MediaController controller = new MediaController(provider(mediaProvider));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/media/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("text", "Hallo"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
    }

    @Test
    void ttsRejectsWhenUnavailable() throws Exception {
        MediaProvider mediaProvider = mock(MediaProvider.class);
        when(mediaProvider.isAvailable()).thenReturn(false);

        MediaController controller = new MediaController(provider(mediaProvider));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/media/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("text", "Hallo"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sttReturnsTranscription() throws Exception {
        MediaProvider mediaProvider = mock(MediaProvider.class);
        when(mediaProvider.isAvailable()).thenReturn(true);
        when(mediaProvider.speechToText(new byte[] {9, 8}, "mp3")).thenReturn("Transkript");
        when(mediaProvider.providerName()).thenReturn("test");

        MediaController controller = new MediaController(provider(mediaProvider));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MockMultipartFile file = new MockMultipartFile("file", "audio.mp3", "audio/mp3", new byte[] {9, 8});
        mockMvc.perform(multipart("/api/v1/media/stt").file(file).param("format", "mp3"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"text\":\"Transkript\"}"));
    }

    @Test
    void sttRejectsWhenUnavailable() throws Exception {
        MediaProvider mediaProvider = mock(MediaProvider.class);
        when(mediaProvider.isAvailable()).thenReturn(false);

        MediaController controller = new MediaController(provider(mediaProvider));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(multipart("/api/v1/media/stt").file(new MockMultipartFile("file", new byte[] {1})))
                .andExpect(status().isBadRequest());
    }

    @Test
    void providerNameIsExposed() {
        MediaProvider mediaProvider = mock(MediaProvider.class);
        when(mediaProvider.providerName()).thenReturn("openai");
        assertThat(mediaProvider.providerName()).isEqualTo("openai");
    }
}