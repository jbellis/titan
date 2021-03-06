
package com.thinkaurelius.titan.core;


/**
 * TitanKey is an extension of {@link TitanType} for properties.
 * <p/>
 * In addition to {@link TitanType}, TitanKey defines:
 * <ul>
 * <li><strong>Data Type:</strong> The accepted types of attribute values.</li>
 * <li><strong>Index:</strong> Whether attribute values are indexed. If a property key is configured to be indexed,
 * then all properties with that key are indexed which means one can retrieve vertices for that key and a value
 * via {@link com.thinkaurelius.titan.core.TitanTransaction#query()}. The type of index can be configured individually
 * for each key and each element type. For instance, one can define only vertices to be indexed for a particular key.</li>
 * </ul>
 *
 * @author Matthias Br&ouml;cheler (me@matthiasb.com)
 * @see TitanType
 */
public interface TitanKey extends TitanType {

    /**
     * Returns the data type for this property key.
     * The attributes of all properties of this type must be an instance of this data type.
     *
     * @return Data type for this property key.
     */
    public Class<?> getDataType();

    /**
     * The {@link Cardinality} of this property key.
     * @return
     */
    public Cardinality getCardinality();

}
