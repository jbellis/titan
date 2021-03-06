package com.thinkaurelius.titan.blueprints;

import com.thinkaurelius.titan.CassandraStorageSetup;
import com.thinkaurelius.titan.diskstorage.configuration.WriteConfiguration;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */

public class EmbeddedCassandraBlueprintsTest extends BasicBlueprintsTest {

    @Override
    protected WriteConfiguration getGraphConfig() {
        return CassandraStorageSetup.getEmbeddedGraphConfiguration(getClass().getSimpleName());
    }

}
