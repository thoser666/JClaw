package biz.brumm.domain.service;

import biz.brumm.domain.model.Plugin;
import biz.brumm.domain.model.PluginOverview;
import biz.brumm.domain.port.in.ListPluginsUseCase;
import biz.brumm.domain.port.out.PluginProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PluginQueryService implements ListPluginsUseCase {

    private final PluginProvider pluginProvider;

    public PluginQueryService(PluginProvider pluginProvider) {
        this.pluginProvider = pluginProvider;
    }

    @Override
    public List<PluginOverview> listPlugins() {
        return pluginProvider.findAll().stream()
                .map(this::toOverview)
                .toList();
    }

    private PluginOverview toOverview(Plugin plugin) {
        return new PluginOverview(plugin.id(), plugin.name(), plugin.version(), plugin.description(),
                plugin.type(), plugin.valid(), plugin.validationMessage());
    }
}
