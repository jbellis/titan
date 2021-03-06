package com.thinkaurelius.titan.graphdb.berkeleyje;

import com.thinkaurelius.titan.BerkeleyJeStorageSetup;
import com.thinkaurelius.titan.diskstorage.configuration.WriteConfiguration;
import com.thinkaurelius.titan.graphdb.TitanGraphTest;

public class BerkeleyJEGraphTest extends TitanGraphTest {

    @Override
    public WriteConfiguration getConfiguration() {
        return BerkeleyJeStorageSetup.getBerkeleyJEGraphConfiguration();
    }

}
