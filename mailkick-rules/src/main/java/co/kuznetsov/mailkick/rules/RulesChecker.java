package co.kuznetsov.mailkick.rules;

import co.kuznetsov.mailkick.model.Rule;
import co.kuznetsov.mailkick.model.ddb.RulesDdbRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up routing rules for an email sender.
 * Performs exact email address lookup first, then falls back to domain lookup.
 */
public final class RulesChecker {

    private static final Logger LOG = LoggerFactory.getLogger(RulesChecker.class);

    private final RulesDdbRepository repository;

    public RulesChecker(RulesDdbRepository repository) {
        this.repository = repository;
    }

    /**
     * Finds a rule for the given sender email address.
     * Tries exact match first, then domain match.
     *
     * @param senderEmail bare email address (e.g. "foo@example.com")
     * @return matching Rule, or empty if none found
     */
    public Optional<Rule> findRule(String senderEmail) {
        if (senderEmail == null || senderEmail.isBlank()) {
            return Optional.empty();
        }
        // 1. Exact email match
        Optional<Rule> exact = repository.findBySender(senderEmail);
        if (exact.isPresent()) {
            LOG.debug("Rule matched by exact sender: {}", senderEmail);
            return exact;
        }

        // 2. Domain match
        String domain = extractDomain(senderEmail);
        if (domain != null) {
            Optional<Rule> byDomain = repository.findBySender(domain);
            if (byDomain.isPresent()) {
                LOG.debug("Rule matched by domain: {}", domain);
                return byDomain;
            }
        }

        LOG.debug("No rule found for sender: {}", senderEmail);
        return Optional.empty();
    }

    private static String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex >= 0 && atIndex < email.length() - 1) {
            return email.substring(atIndex + 1).toLowerCase();
        }
        return null;
    }
}
