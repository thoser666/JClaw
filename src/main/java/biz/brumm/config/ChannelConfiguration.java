package biz.brumm.config;

import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.ChannelStore;
import biz.brumm.domain.service.ChannelService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring-Konfiguration für das Channel-System (P3-01).
 * {@link ChannelService} ist immer verfügbar (für REST-API und Tests).
 * Channel-Adapter werden nur registriert, wenn sie als Beans vorhanden sind.
 */
@Configuration
public class ChannelConfiguration {

    @Bean
    public ChannelService channelService(ChannelStore channelStore,
                                          List<ChannelAdapter> adapters) {
        return new ChannelService(channelStore, adapters);
    }
}
