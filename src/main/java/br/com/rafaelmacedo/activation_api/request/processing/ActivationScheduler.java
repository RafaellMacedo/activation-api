package br.com.rafaelmacedo.activation_api.request.processing;

import br.com.rafaelmacedo.activation_api.request.domain.ActivationStatus;
import br.com.rafaelmacedo.activation_api.request.repository.ActivationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        prefix = "app.processing",
        name = "enabled",
        havingValue = "true"
)
public class ActivationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ActivationScheduler.class);

    private final ActivationRequestRepository repository;
    private final ActivationProcessor processor;
    private final Clock clock;

    public ActivationScheduler(
            ActivationRequestRepository repository,
            ActivationProcessor processor,
            Clock clock
    ) {
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.processing.poll-delay:1000}")
    public void processPendingRequests() {
        var pending = repository
                .findTop10ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        ActivationStatus.PENDING,
                        clock.instant()
                );

        for (var request : pending) {
            try {
                processor.process(request.getId());
            } catch (Exception exception) {
                log.error(
                        "Could not process activation request {}",
                        request.getId(),
                        exception
                );
            }
        }
    }
}