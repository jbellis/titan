package com.thinkaurelius.titan.graphdb.types;

import com.google.common.base.Preconditions;
import com.tinkerpop.blueprints.Direction;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public interface SchemaSource {

    public long getID();

    public String getName();

    public SchemaStatus getStatus();

    public TypeDefinitionMap getDefinition();

    public Iterable<Entry> getRelated(TypeDefinitionCategory def, Direction dir);

    /**
     * Resets the internal caches used to speed up lookups on this schema source.
     * This is needed when the source gets modified.
     */
    public void resetCache();

    public IndexType asIndexType();

    public static class Entry {

        private final SchemaSource schemaType;
        private final Object modifier;

        public Entry(SchemaSource schemaType, Object modifier) {
            Preconditions.checkNotNull(schemaType);
            this.schemaType = schemaType;
            this.modifier = modifier;
        }

        public SchemaSource getSchemaType() {
            return schemaType;
        }

        public Object getModifier() {
            return modifier;
        }
    }

}
