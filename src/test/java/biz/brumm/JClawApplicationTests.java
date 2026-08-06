package biz.brumm;

import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.infrastructure.adapter.in.web.TaskRestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JClawApplicationTests {

    @Autowired
    private ExecuteTaskUseCase executeTaskUseCase;

    @Autowired
    private AiProviderPort aiProviderPort;

    @Autowired
    private TaskRestController taskRestController;

    @Test
    void contextLoads() {
        assertThat(executeTaskUseCase).isNotNull();
        assertThat(aiProviderPort).isNotNull();
        assertThat(taskRestController).isNotNull();
    }

}
