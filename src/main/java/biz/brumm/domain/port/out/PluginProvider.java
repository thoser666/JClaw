package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Plugin;

import java.util.List;

/**
 * Port fuer das Lesen von Plugin-Manifesten (Control-Plane, ohne Codeausfuehrung).
 */
public interface PluginProvider {

    List<Plugin> findAll();
}
