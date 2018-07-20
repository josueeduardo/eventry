package io.joshworks.fstore.log.appender.appenders;

import io.joshworks.fstore.log.appender.Config;
import io.joshworks.fstore.log.appender.LogAppender;
import io.joshworks.fstore.log.segment.LogSegment;

public class SimpleLogAppender<T> extends LogAppender<T, LogSegment<T>> {

    public SimpleLogAppender(Config<T> config) {
        super(config, LogSegment::new);
    }

}