package com.thinkaurelius.titan.graphdb.types;

import com.thinkaurelius.titan.core.TitanType;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public interface TypeInspector {

    public TitanType getExistingType(long id);

    public boolean containsType(String name);

    public TitanType getType(String name);

}
