package com.thinkaurelius.titan.core.olap;

import com.thinkaurelius.titan.core.TitanVertexQuery;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

/**
 * Builder to define and configure an {@link OLAPJob} execution.
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public interface OLAPJobBuilder<S> {

    /**
     * Configures the {@link OLAPJob} vertex-centric program to execute on all vertices.
     * @param job
     * @return
     */
    public OLAPJobBuilder<S> setJob(OLAPJob job);

    /**
     * Defines the name of the key to be used as the dedicated "state" key. Retrieving the property value for this
     * key will return the vertex state.
     *
     * @param stateKey
     * @return
     */
    public OLAPJobBuilder<S> setStateKey(String stateKey);

    /**
     * Sets an {@link StateInitializer} function to initialize the vertex state on demand.
     *
     * @param initial
     * @return
     */
    public OLAPJobBuilder<S> setInitializer(StateInitializer<S> initial);

    /**
     * Set the initial state of the vertices where the key is the vertex id and the value is the state
     * of the vertex.
     * </p>
     * Note, that the OLAP executor might operate directly on the provided map to preserve memory. Providing an immutable map
     * can therefore lead to exceptions. If you wish to preserve the map (or use it elsewhere independently) make sure
     * to pass in a copy of the map.
     *
     * @param values
     * @return
     */
    public OLAPJobBuilder<S> setInitialState(Map<Long,S> values);

    /**
     * If the exact number of vertices to be processed is know a priori, it can be specified
     * via this method to make memory allocation more efficient. Setting is value is optional
     * and does not impact correctness. Providing the exact number of vertices might make
     * execution faster.
     *
     * @param numVertices
     * @return
     */
    public OLAPJobBuilder<S> setNumVertices(long numVertices);

    /**
     * Configure the number of threads to execute the configured {@link OLAPJob}.
     *
     * @param numThreads
     * @return
     */
    public OLAPJobBuilder<S> setNumProcessingThreads(int numThreads);

    /**
     * Adds a new vertex query to this job. The vertex queries specify which edges and properties will be accessible when
     * the configured {@link OLAPJob} executes. Only the data that can be retrieved through previously configured queries
     * will be accessible during execution.
     *
     * @return
     */
    public OLAPQueryBuilder<S> addQuery();

    /**
     * Starts the execution of this job and returns the computed vertex states as a map.
     *
     * @return
     */
    public Future<Map<Long,S>> execute();

}
