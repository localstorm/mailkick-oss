package co.kuznetsov.mailkick.rules;

import co.kuznetsov.mailkick.jmap.EmailMover;
import co.kuznetsov.mailkick.jmap.MailboxResolver;
import co.kuznetsov.mailkick.model.Email;
import co.kuznetsov.mailkick.model.MailKickConfig;
import co.kuznetsov.mailkick.model.Rule;
import co.kuznetsov.mailkick.model.RuleType;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a matched rule to an email by issuing the appropriate JMAP operations.
 */
public final class RuleExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(
        RuleExecutor.class
    );

    private final EmailMover mover;
    private final MailboxResolver resolver;
    private final java.util.function.Supplier<MailKickConfig> configSupplier;

    public RuleExecutor(EmailMover mover, MailboxResolver resolver) {
        this(mover, resolver, () -> null);
    }

    /**
     * Constructs a {@code RuleExecutor} with a live config supplier for folder policy resolution.
     *
     * @param mover          JMAP email mover
     * @param resolver       mailbox resolver
     * @param configSupplier supplies the current {@link MailKickConfig} on each call
     */
    public RuleExecutor(
        EmailMover mover,
        MailboxResolver resolver,
        java.util.function.Supplier<MailKickConfig> configSupplier
    ) {
        this.mover = mover;
        this.resolver = resolver;
        this.configSupplier = configSupplier;
    }

    /**
     * Applies the given rule to the specified email.
     *
     * @param rule    the matched rule to apply
     * @param emailId the JMAP email ID to act on
     * @param email   the normalised email, used for logging
     * @return outcome indicating whether further LLM processing is needed
     * @throws IOException if any JMAP operation fails
     */
    public RuleExecutionOutcome execute(Rule rule, String emailId, Email email)
        throws IOException {
        RuleType type = rule.getRuleType();

        switch (type) {
            case MOVE_TO_FOLDER_NO_PROCESSING: {
                String mailboxId = resolver.getMailboxId(
                    rule.getTargetFolder()
                );
                mover.moveToMailboxAndSetRead(emailId, mailboxId);
                LOG.info(
                    "→ {} | from={} | subject={}",
                    rule.getTargetFolder(),
                    email.getFrom(),
                    email.getSubject()
                );
                return RuleExecutionOutcome.handled();
            }
            case SPAM: {
                MailKickConfig cfg = configSupplier.get();
                String spamFolder = (cfg != null)
                    ? cfg.getResolvedSpamFolder()
                    : null;
                String spamId = (spamFolder != null)
                    ? resolver.getMailboxId(spamFolder)
                    : resolver.getSpamId();
                String spamKey = (spamFolder != null) ? spamFolder : "spam";
                boolean markAsRead =
                    cfg == null || !cfg.shouldMarkUnread(spamKey);
                mover.setRead(emailId, markAsRead);
                mover.moveToMailbox(emailId, spamId);
                LOG.info(
                    "→ Spam | from={} | subject={}",
                    email.getFrom(),
                    email.getSubject()
                );
                return RuleExecutionOutcome.handled();
            }
            case TRASH: {
                mover.moveToMailboxAndSetRead(emailId, resolver.getTrashId());
                LOG.info(
                    "→ Trash | from={} | subject={}",
                    email.getFrom(),
                    email.getSubject()
                );
                return RuleExecutionOutcome.handled();
            }
            case ERASE: {
                mover.destroy(emailId);
                LOG.info(
                    "erased | from={} | subject={}",
                    email.getFrom(),
                    email.getSubject()
                );
                return RuleExecutionOutcome.handled();
            }
            case MOVE_TO_FOLDER_WITH_PROCESSING: {
                String targetMailboxId = resolver.getMailboxId(
                    rule.getTargetFolder()
                );
                mover.moveToMailbox(emailId, targetMailboxId);
                MailKickConfig cfg = configSupplier.get();
                boolean markAsRead = cfg == null || !cfg.shouldMarkUnread(rule.getTargetFolder());
                mover.setRead(emailId, markAsRead);
                LOG.info(
                    "→ {} (continuing to LLM) | from={} | subject={}",
                    rule.getTargetFolder(),
                    email.getFrom(),
                    email.getSubject()
                );
                return RuleExecutionOutcome.processWithLlm(
                    rule.getPromptName()
                );
            }
            default:
                throw new IOException("Unknown rule type: " + type);
        }
    }
}
