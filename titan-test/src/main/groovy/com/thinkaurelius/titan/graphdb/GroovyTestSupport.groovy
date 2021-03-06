package com.thinkaurelius.titan.graphdb

import com.thinkaurelius.titan.diskstorage.configuration.WriteConfiguration

import static org.junit.Assert.*

import org.junit.After
import org.junit.Before
import org.junit.Rule;
import org.junit.rules.TestName;
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.google.common.base.Preconditions
import com.tinkerpop.blueprints.Vertex
import com.thinkaurelius.titan.core.TitanVertex
import com.thinkaurelius.titan.core.TitanGraph
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration
import com.thinkaurelius.titan.graphdb.database.StandardTitanGraph
import com.thinkaurelius.titan.testutil.gen.Schema
import com.thinkaurelius.titan.testutil.gen.GraphGenerator
import com.tinkerpop.gremlin.groovy.Gremlin
import com.thinkaurelius.titan.diskstorage.StorageException

import java.util.zip.GZIPInputStream

abstract class GroovyTestSupport {

    private static final Logger log = LoggerFactory.getLogger(GroovyTestSupport)

    @Rule
    public TestName testName = new TestName()

    // Graph generation settings
    public static final int VERTEX_COUNT = 10 * 100
    public static final int EDGE_COUNT = VERTEX_COUNT * 5

    // Query execution setting defaults
    public static final int DEFAULT_TX_COUNT = 3
    public static final int DEFAULT_VERTICES_PER_TX = 100
    public static final int DEFAULT_ITERATIONS = DEFAULT_TX_COUNT * DEFAULT_VERTICES_PER_TX

    public static final String RELATION_FILE = "../titan-test/data/v10k.graphml.gz"

    // Mutable state

    /*  JUnit constructs a new test class instance before executing each test method. 
     * Ergo, each test method gets its own Random instance. 
     * The seed is arbitrary and carries no special significance,
     * but we keep the see fixed for repeatability.
     */
    protected Random random = new Random(7)
    protected GraphGenerator gen
    protected Schema schema
    protected TitanGraph graph
    protected WriteConfiguration conf

    static {
        Gremlin.load()
    }

    GroovyTestSupport(WriteConfiguration conf) throws StorageException {
        this.conf = conf
    }

    @Before
    void open() {
//        Preconditions.checkArgument(TX_COUNT * DEFAULT_OPS_PER_TX <= VERTEX_COUNT);

        if (null == graph) {
            try {
                graph = getGraph()
            } catch (StorageException e) {
                throw new RuntimeException(e)
            }
        }
        if (null == schema) {
            schema = getSchema()
        }
    }

    @After
    void rollback() {
        if (null != graph)
            graph.rollback()
    }

    void close() {
        if (null != graph)
            graph.shutdown()
    }

    protected abstract StandardTitanGraph getGraph() throws StorageException;

    protected abstract Schema getSchema();

    /*
     * Helper methods
     */

    protected void sequentialUidTask(int verticesPerTx = DEFAULT_VERTICES_PER_TX, closure) {
        chunkedSequentialUidTask(1, verticesPerTx, { tx, vbuf, vloaded ->
            assert 1 == vloaded
            assert 1 == vbuf.length
            def v = vbuf[0]
            closure.call(tx, v)
        })
    }

    protected void chunkedSequentialUidTask(int chunksize = DEFAULT_VERTICES_PER_TX, int verticesPerTx = DEFAULT_VERTICES_PER_TX, closure) {

        /*
         * Need this condition because of how we handle transactions and buffer
         * Vertex objects.  If this divisibility constraint were violated, then
         * we would end up passing Vertex instances from one or more committed
         * transactions as if those instances were not stale.
         */
        Preconditions.checkArgument(0 == verticesPerTx % chunksize)

        long count = DEFAULT_TX_COUNT * verticesPerTx
        long offset = Math.abs(random.nextLong()) % schema.getMaxUid()
        def uids = new SequentialLongIterator(count, schema.getMaxUid(), offset)
        def tx = graph.newTransaction()
        TitanVertex[] vbuf = new TitanVertex[chunksize]
        int vloaded = 0

        while (uids.hasNext()) {
            long u = uids.next()
            Vertex v = tx.getVertex(Schema.UID_PROP, u)
            assertNotNull(v)
            vbuf[vloaded++] = v
            if (vloaded == chunksize) {
                closure.call(tx, vbuf, vloaded)
                vloaded = 0
                tx.commit()
                tx = graph.newTransaction()
            }
        }

        if (0 < vloaded) {
            closure.call(tx, vbuf, vloaded)
            tx.commit()
        } else {
            tx.rollback()
        }
    }

    protected void supernodeTask(closure) {
        long uid = schema.getSupernodeUid()
        String label = schema.getSupernodeOutLabel()
        assertNotNull(label)
        String pkey = schema.getSortKeyForLabel(label)
        assertNotNull(pkey)

        def tx = graph.newTransaction()
        def v = tx.getVertex(Schema.UID_PROP, uid)
//            def v = graph.V(Schema.UID_PROP, uid).next()
        assertNotNull(v)
        closure(v, label, pkey)
        tx.commit()
    }

    protected void standardIndexEdgeTask(closure) {
        final int keyCount = schema.getEdgePropKeys()

        def tx = graph.newTransaction()
        int value = -1
        for (int p = 0; p < schema.getEdgePropKeys(); p++) {
            for (int i = 0; i < 5; i++) {
                if (++value >= schema.getMaxEdgePropVal())
                    value = 0
                closure(tx, schema.getEdgePropertyName(p), value)
            }
        }
        tx.commit()
    }

    protected void standardIndexVertexTask(closure) {
        final int keyCount = schema.getVertexPropKeys()

        def tx = graph.newTransaction()
        int value = -1
        for (int p = 0; p < schema.getVertexPropKeys(); p++) {
            for (int i = 0; i < 5; i++) {
                if (++value >= schema.getMaxVertexPropVal())
                    value = 0
                closure(tx, schema.getVertexPropertyName(p), value)
            }

        }
        tx.commit()
    }

    protected void initializeGraph(TitanGraph g) throws StorageException {
        log.info("Initializing graph...");
        long before = System.currentTimeMillis()
        Schema schema = getSchema();
        GraphGenerator generator = new GraphGenerator(schema);
        GraphDatabaseConfiguration graphconfig = new GraphDatabaseConfiguration(conf);
        graphconfig.getBackend().clearStorage();
//        generator.generate(g);
        try {
            generator.generateTypesAndLoadData(g, new GZIPInputStream(new FileInputStream(RELATION_FILE)))
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        long after = System.currentTimeMillis()
        long duration = after - before
        if (15 * 1000 <= duration) {
            log.warn("Initialized graph (" + duration + " ms).")
        } else {
            log.info("Initialized graph (" + duration + " ms).")
        }
    }
}