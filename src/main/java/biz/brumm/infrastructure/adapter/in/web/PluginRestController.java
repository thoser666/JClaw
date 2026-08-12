package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.PluginOverview;
import biz.brumm.domain.port.in.ListPluginsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plugins")
public class PluginRestController {

    private final ListPluginsUseCase listPluginsUseCase;

    public PluginRestController(ListPluginsUseCase listPluginsUseCase) {
        this.listPluginsUseCase = listPluginsUseCase;
    }

    @GetMapping
    public ResponseEntity<List<PluginOverview>> listPlugins() {
        return ResponseEntity.ok(listPluginsUseCase.listPlugins());
    }
}
