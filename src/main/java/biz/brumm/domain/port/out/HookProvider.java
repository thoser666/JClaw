package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Hook;

import java.util.List;

/**
 * Liefert die im konfigurierten Verzeichnis gefundenen Hooks (HOOK.md-Format).
 */
public interface HookProvider {

    List<Hook> findAll();

    List<Hook> findByStage(String stage);
}
