package com.thinkaurelius.titan.graphdb.types;

import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.core.Parameter;
import com.thinkaurelius.titan.core.ParameterType;
import com.thinkaurelius.titan.core.TitanKey;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class ParameterIndexField extends IndexField {

    private final Parameter[] parameters;

    private ParameterIndexField(TitanKey key, Parameter[] parameters) {
        super(key);
        Preconditions.checkNotNull(parameters);
        this.parameters=parameters;
    }

    public SchemaStatus getStatus() {
        return ParameterType.STATUS.findParameter(parameters, SchemaStatus.DISABLED);
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public static ParameterIndexField of(TitanKey key, Parameter... parameters) {
        return new ParameterIndexField(key,parameters);
    }


}
