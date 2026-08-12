package biz.brumm.domain.port.in;

import biz.brumm.domain.model.PluginOverview;

import java.util.List;

public interface ListPluginsUseCase {

    List<PluginOverview> listPlugins();
}
