package biz.brumm.domain.service;

import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginOverview;
import biz.brumm.domain.model.PluginType;
import biz.brumm.domain.port.out.PluginProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginQueryServiceTest {

    @Mock
    private PluginProvider pluginProvider;

    @Test
    void listPluginsMapsManifestsToOverviews() {
        PluginQueryService service = new PluginQueryService(pluginProvider);
        when(pluginProvider.findAll()).thenReturn(List.of(
                new Plugin("acme/demo", "Demo", "1.0.0", "Test.", PluginType.OPENCLAW,
                        "/plugins/demo", true, ""),
                new Plugin("broken", null, null, null, PluginType.OPENCLAW,
                        "/plugins/broken", false, "Feld 'id' fehlt.")));

        List<PluginOverview> overviews = service.listPlugins();

        assertThat(overviews).extracting(PluginOverview::id).containsExactly("acme/demo", "broken");
        assertThat(overviews.get(0).type()).isEqualTo(PluginType.OPENCLAW);
        assertThat(overviews.get(0).valid()).isTrue();
        assertThat(overviews.get(0).validationMessage()).isEmpty();
        assertThat(overviews.get(1).valid()).isFalse();
        assertThat(overviews.get(1).validationMessage()).isEqualTo("Feld 'id' fehlt.");
    }

    @Test
    void listPluginsReturnsEmptyWhenNoPluginsAvailable() {
        PluginQueryService service = new PluginQueryService(pluginProvider);
        when(pluginProvider.findAll()).thenReturn(List.of());

        assertThat(service.listPlugins()).isEmpty();
    }
}
