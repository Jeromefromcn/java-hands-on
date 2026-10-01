package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.messaging.NoticeChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The notice channel of a developer's machine: it writes that a notice went out to the log. A real
 * channel is an e-mail or a message to a desk, and is outside this stage.
 *
 * <p>The line names the position, the counterparty's id, the security and the currency of the
 * shortfall, and not the amount: an amount in a log is a copy of the ledger in a system with a
 * different retention and audience. See {@code docs/logging.md}.
 */
class LoggingNoticeChannel implements NoticeChannel {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingNoticeChannel.class);

  @Override
  public void send(MarginCallNotice notice) {
    LOG.info(
        "MARGIN CALL notice to {}: position {} in {} is short in {} on {}",
        notice.counterparty().value(),
        notice.position().value(),
        notice.security().value(),
        notice.shortfall().currency().getCurrencyCode(),
        notice.calledOn());
  }
}
