package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.messaging.NoticeChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The notice channel of a developer's machine: it writes the notice to the log. A real channel is
 * an e-mail or a message to a desk, and is outside this stage.
 */
class LoggingNoticeChannel implements NoticeChannel {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingNoticeChannel.class);

  @Override
  public void send(MarginCallNotice notice) {
    LOG.info(
        "MARGIN CALL notice to {}: position {} in {} is short {} {} on {}",
        notice.counterparty().value(),
        notice.position().value(),
        notice.security().value(),
        notice.shortfall().amount().toPlainString(),
        notice.shortfall().currency().getCurrencyCode(),
        notice.calledOn());
  }
}
