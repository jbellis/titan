package com.thinkaurelius.titan.diskstorage.log;

import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.core.time.Timepoint;
import com.thinkaurelius.titan.core.time.Timestamps;

import java.util.concurrent.TimeUnit;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class ReadMarker {

    private final String identifier;
    private final Timepoint startTime;

    private ReadMarker(String identifier, Timepoint startTime) {
        this.identifier = identifier;
        this.startTime = startTime;
    }

    /**
     * Whether this read marker has a configured identifier
     * @return
     */
    public boolean hasIdentifier() {
        return identifier!=null;
    }

    /**
     * Returns the configured identifier of this marker or throws an exception if none exists.
     * @return
     */
    public String getIdentifier() {
        Preconditions.checkArgument(identifier!=null,"ReadMarker does not have a configured identifier");
        return identifier;
    }

    /**
     * Returns the start time of this marker in microseconds
     * @return
     */
    public Timepoint getStartTime() {
        return startTime;
    }

    /**
     * Starts reading the log such that it will start with the first entry written after now.
     *
     * @return
     */
    public static ReadMarker fromNow() {
        return new ReadMarker(null, Timestamps.MICRO.getTime());
    }

    /**
     * Starts reading the log from the given timestamp onward. The specified timestamp is included.
     * @param timestamp
     * @return
     */
    public static ReadMarker fromTime(long timestamp, TimeUnit unit) {
        return new ReadMarker(null, new Timepoint(timestamp, unit));
    }

    /**
     * Starts reading the log from the last recorded point in the log for the given id.
     * If the log has a record of such an id, it will use it as the starting point.
     * If not, it will start from the given timestamp and set it as the first read record for the given id.
     * <p/>
     * Identified read markers of this kind are useful to continuously read from the log. In the case of failure,
     * the last read record can be recovered for the id and log reading can be resumed from there. Note, that some
     * records might be read twice in that event depending on the guarantees made by a particular implementation.
     *
     * @param id
     * @param timestamp
     * @return
     */
    public static ReadMarker fromIdentifierOrTime(String id, long timestamp, TimeUnit unit) {
        return new ReadMarker(id, new Timepoint(timestamp, unit));
    }

}
