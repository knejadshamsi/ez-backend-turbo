package ez.backend.turbo.services;

import ez.backend.turbo.database.DraftRepository;
import ez.backend.turbo.utils.IdGenerator;
import ez.backend.turbo.utils.L;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DraftService {

    private static final Logger log = LogManager.getLogger(DraftService.class);

    private final DraftRepository draftRepository;
    private final IdGenerator idGenerator;

    public DraftService(DraftRepository draftRepository, IdGenerator idGenerator) {
        this.draftRepository = draftRepository;
        this.idGenerator = idGenerator;
    }

    public UUID createDraft(String inputData, String sessionData) {
        UUID draftId = idGenerator.generate();
        draftRepository.create(draftId, inputData, sessionData, Instant.now());
        log.info("{}: {}", L.msg("draft.created"), draftId);
        return draftId;
    }

    public Optional<Map<String, Object>> getDraft(UUID draftId) {
        return draftRepository.findById(draftId);
    }

    public void updateDraft(UUID draftId, String inputData, String sessionData) {
        int updated = draftRepository.update(draftId, inputData, sessionData);
        if (updated == 0) {
            throw new IllegalArgumentException(L.msg("draft.not.found") + ": " + draftId);
        }
        log.info("{}: {}", L.msg("draft.updated"), draftId);
    }

    public void deleteDraft(UUID draftId) {
        int deleted = draftRepository.deleteById(draftId);
        if (deleted == 0) {
            throw new IllegalArgumentException(L.msg("draft.not.found") + ": " + draftId);
        }
        log.info("{}: {}", L.msg("draft.deleted"), draftId);
    }
}
