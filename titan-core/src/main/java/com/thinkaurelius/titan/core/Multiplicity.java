package com.thinkaurelius.titan.core;

import com.google.common.base.Preconditions;
import com.tinkerpop.blueprints.Direction;

/**
 * The multiplicity of edges between vertices for a given label.
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public enum Multiplicity {

    /**
     * The given edge label specifies a multi-graph, meaning that the multiplicity is not constrained and that
     * there may be multiple edges of this label between a given pair of vertices.
     *
     * @link http://en.wikipedia.org/wiki/Multigraph
     */
    MULTI,

    /**
     * The given edge label specifies a simple graph, meaning that the multiplicity is not constrained but that there
     * can only be at most a single edge of this label between a given pair of vertices.
     */
    SIMPLE,

    /**
     * There can only be a single in-edge of this label for a given vertex but multiple out-edges (i.e. in-unique)
     */
    ONE2MANY,

    /**
     * There can only be a single out-edge of this label for a given vertex but multiple in-edges (i.e. out-unique)
     */
    MANY2ONE,

    /**
     * There can be only a single in and out-edge of this label for a given vertex (i.e. unique in both directions).
     */
    ONE2ONE;

    public Cardinality getCardinality() {
        switch (this) {
            case MULTI: return Cardinality.LIST;
            case SIMPLE: return Cardinality.SET;
            case MANY2ONE: return Cardinality.SINGLE;
            default: throw new AssertionError("Invalid multiplicity: " + this);
        }
    }

    public boolean isConstrained() {
        return this!=MULTI;
    }

    public static Multiplicity convert(Cardinality cardinality) {
        Preconditions.checkNotNull(cardinality);
        switch(cardinality) {
            case LIST: return MULTI;
            case SET: return SIMPLE;
            case SINGLE: return MANY2ONE;
            default: throw new AssertionError("Unknown cardinality: " + cardinality);
        }
    }

    public boolean isUnique(Direction direction) {
        switch (direction) {
            case IN:
                return this==ONE2MANY || this==ONE2ONE;
            case OUT:
                return this==MANY2ONE || this==ONE2ONE;
            case BOTH:
                return this==ONE2ONE;
            default: throw new AssertionError("Unknown direction: " + direction);
        }
    }

}
