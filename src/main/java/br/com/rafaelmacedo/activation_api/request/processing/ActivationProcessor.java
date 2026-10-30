package br.com.rafaelmacedo.activation_api.request.processing;

import br.com.rafaelmacedo.activation_api.provider.client.ProviderActivationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ActivationProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationProcessingTransactions transactions;
    private final ProviderActivationClient providerClient;

    public ActivationProcessor(
            ActivationProcessingTransactions transactions,
            ProviderActivationClient providerClient
    ) {
        this.transactions = transactions;
        this.providerClient = providerClient;
    }

    public void process(UUID requestId) {
        ClaimedActivation claim;

        try {
            var claimed = transactions.claim(requestId);

            if (claimed.isEmpty()) {
                return;
            }

            claim = claimed.get();
        } catch (ObjectOptimisticLockingFailureException exception) {
            log.debug(
                    "Activation request {} was modified during claim",
                    requestId
            );
            return;
        }

        var result = providerClient.activate(claim.payload());

        try {
            boolean applied = transactions.applyResult(claim, result);

            if (applied) {
                log.info(
                        "Processed activation request {} attempt {} "
                                + "with outcome {} and HTTP status {}",
                        requestId,
                        claim.attemptCount(),
                        result.outcome(),
                        result.httpStatus()
                );
            } else {
                log.debug(
                        "Ignored outdated result for activation request {}",
                        requestId
                );
            }
        } catch (ObjectOptimisticLockingFailureException exception) {
            log.warn(
                    "Activation request {} changed before its result "
                            + "could be applied",
                    requestId
            );
        }
    }
}