package biz.brumm.domain.port.out;

import biz.brumm.domain.model.ApiKey;

import java.util.List;
import java.util.Optional;

public interface ApiKeyStore {

    Optional<ApiKey> findByTokenHash(String tokenHash);

    List<ApiKey> findAll();

    ApiKey save(ApiKey apiKey);

    void deleteById(String id);
}
