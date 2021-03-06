package com.thinkaurelius.titan.graphdb.idmanagement;


import com.thinkaurelius.titan.diskstorage.ReadBuffer;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.WriteBuffer;
import com.thinkaurelius.titan.diskstorage.util.BufferUtil;
import com.thinkaurelius.titan.diskstorage.util.WriteByteBuffer;
import com.thinkaurelius.titan.graphdb.database.idhandling.IDHandler;
import com.thinkaurelius.titan.graphdb.database.idhandling.VariableLong;
import com.thinkaurelius.titan.graphdb.database.serialize.DataOutput;
import com.thinkaurelius.titan.graphdb.database.serialize.Serializer;
import com.thinkaurelius.titan.graphdb.database.serialize.StandardSerializer;
import com.thinkaurelius.titan.graphdb.internal.RelationCategory;
import com.thinkaurelius.titan.graphdb.types.system.*;
import com.thinkaurelius.titan.testutil.RandomGenerator;
import com.tinkerpop.blueprints.Direction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

import static org.junit.Assert.*;

public class IDManagementTest {

    private static final Logger log = LoggerFactory.getLogger(IDManagementTest.class);

    private static final Random random = new Random();

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void EntityIDTest() {
        testEntityID(21, 1234123, 2341);
        testEntityID(25, 582919, 9921239);
        testEntityID(4, 1, 14);
        testEntityID(10, 903392, 1);
        testEntityID(0, 242342, 0);
        testEntityID(0, 242342, 0);
        try {
            testEntityID(0, 242342, 1);
            assertTrue(false);
        } catch (IllegalArgumentException e) {
        }
        ;
        try {
            testEntityID(0, -11, 0);
            assertTrue(false);
        } catch (IllegalArgumentException e) {
        }
        ;

    }


    public void testEntityID(int partitionBits, long count, int partition) {
        IDManager eid = new IDManager(partitionBits);
        IDInspector isp = eid.getIdInspector();

        assertTrue(eid.getPartitionBound()>0);
        assertTrue(eid.getPartitionBound()<=1l+Integer.MAX_VALUE);
        assertTrue(eid.getRelationCountBound()>0);
        assertTrue(eid.getRelationTypeCountBound()>0);
        assertTrue(eid.getVertexCountBound()>0);

        long id = eid.getVertexID(count, partition);
        assertTrue(isp.isVertexId(id));
        assertEquals(eid.getPartitionId(id), partition);

        id = eid.getRelationID(count, partition);
        assertEquals(eid.getPartitionId(id), partition);

        id = eid.getSchemaId(IDManager.VertexIDType.UserPropertyKey, count);
        assertTrue(isp.isPropertyKeyId(id));
        assertTrue(isp.isRelationTypeId(id));
        assertFalse(isp.isSystemRelationTypeId(id));

        id = eid.getSchemaId(IDManager.VertexIDType.SystemPropertyKey, count);
        assertTrue(isp.isPropertyKeyId(id));
        assertTrue(isp.isRelationTypeId(id));
        assertTrue(isp.isSystemRelationTypeId(id));


        id = eid.getSchemaId(IDManager.VertexIDType.UserEdgeLabel,count);
        assertTrue(isp.isEdgeLabelId(id));
        assertTrue(isp.isRelationTypeId(id));

        id = IDManager.getTemporaryVertexID(IDManager.VertexIDType.Vertex,1);
        assertTrue(id<0);
        assertTrue(IDManager.VertexIDType.Vertex.is(id));

        id = IDManager.getTemporaryVertexID(IDManager.VertexIDType.UserEdgeLabel,2);
        assertTrue(id<0);
        assertTrue(IDManager.VertexIDType.UserEdgeLabel.is(id));

        id = IDManager.getTemporaryRelationID(101);
        assertTrue(id<0);

        id = IDManager.getTemporaryVertexID(IDManager.VertexIDType.HiddenVertex,1011);
        assertTrue(id<0);
        assertTrue(IDManager.VertexIDType.Hidden.is(id));

        try {
            id = IDManager.getTemporaryVertexID(IDManager.VertexIDType.RelationType,5);
            fail();
        } catch (IllegalArgumentException e) {}

    }

    @Test
    public void edgeTypeIDTest() {
        int partitionBits = 21;
        IDManager eid = new IDManager(partitionBits);
        IDInspector isp = eid.getIdInspector();
        int trails = 1000000;
        assertEquals(eid.getPartitionBound(), (1l << partitionBits));

        Serializer serializer = new StandardSerializer();
        for (int t = 0; t < trails; t++) {
            long count = RandomGenerator.randomLong(1, eid.getRelationTypeCountBound());
            long id;
            IDHandler.DirectionID dirID;
            RelationCategory type;
            if (Math.random() < 0.5) {
                id = eid.getSchemaId(IDManager.VertexIDType.UserEdgeLabel,count);
                assertTrue(isp.isEdgeLabelId(id));
                assertFalse(isp.isSystemRelationTypeId(id));
                type = RelationCategory.EDGE;
                if (Math.random() < 0.5)
                    dirID = IDHandler.DirectionID.EDGE_IN_DIR;
                else
                    dirID = IDHandler.DirectionID.EDGE_OUT_DIR;
            } else {
                type = RelationCategory.PROPERTY;
                id = eid.getSchemaId(IDManager.VertexIDType.UserPropertyKey,count);
                assertTrue(isp.isPropertyKeyId(id));
                assertFalse(isp.isSystemRelationTypeId(id));
                dirID = IDHandler.DirectionID.PROPERTY_DIR;
            }
            assertTrue(isp.isRelationTypeId(id));

            StaticBuffer b = IDHandler.getEdgeType(id, dirID, false);
//            System.out.println(dirID);
//            System.out.println(getBinary(id));
//            System.out.println(getBuffer(b.asReadBuffer()));
            ReadBuffer rb = b.asReadBuffer();
            IDHandler.EdgeTypeParse parse = IDHandler.readEdgeType(rb);
            assertEquals(id,parse.typeId);
            assertEquals(dirID, parse.dirID);
            assertFalse(rb.hasRemaining());

            //Inline edge type
            WriteBuffer wb = new WriteByteBuffer(9);
            IDHandler.writeInlineEdgeType(wb, id);
            long newId = IDHandler.readInlineEdgeType(wb.getStaticBuffer().asReadBuffer());
            assertEquals(id,newId);

            //Compare to Kryo
            DataOutput out = serializer.getDataOutput(10);
            IDHandler.writeEdgeType(out, id, dirID, false);
            assertEquals(b, out.getStaticBuffer());

            //Make sure the bounds are right
            StaticBuffer[] bounds = IDHandler.getBounds(type);
            assertTrue(bounds[0].compareTo(b)<0);
            assertTrue(bounds[1].compareTo(b)>0);
            bounds = IDHandler.getBounds(RelationCategory.RELATION);
            assertTrue(bounds[0].compareTo(b)<0);
            assertTrue(bounds[1].compareTo(b)>0);
        }
    }

    private static final SystemType[] SYSTEM_TYPES = {BaseKey.VertexExists, BaseKey.TypeDefinitionProperty,
            BaseLabel.TypeDefinitionEdge, ImplicitKey.VISIBILITY, ImplicitKey.TIMESTAMP};

    @Test
    public void writingInlineEdgeTypes() {
        int numTries = 100;
        WriteBuffer out = new WriteByteBuffer(8*numTries);
        for (SystemType t : SYSTEM_TYPES) {
            IDHandler.writeInlineEdgeType(out,t.getID());
        }
        for (long i=1;i<=numTries;i++) {
            IDHandler.writeInlineEdgeType(out,IDManager.getSchemaId(IDManager.VertexIDType.UserEdgeLabel,i*1000));
        }

        ReadBuffer in = out.getStaticBuffer().asReadBuffer();
        for (SystemType t : SYSTEM_TYPES) {
            assertEquals(t, SystemTypeManager.getSystemType(IDHandler.readInlineEdgeType(in)));
        }
        for (long i=1;i<=numTries;i++) {
            assertEquals(i * 1000, IDManager.stripEntireRelationTypePadding(IDHandler.readInlineEdgeType(in)));
        }
    }

    @Test
    public void testDirectionPrefix() {
        for (RelationCategory type : RelationCategory.values()) {
            StaticBuffer[] bounds = IDHandler.getBounds(type);
            assertEquals(1,bounds[0].length());
            assertEquals(1,bounds[1].length());
            assertTrue(bounds[0].compareTo(bounds[1])<0);
            assertTrue(bounds[1].compareTo(BufferUtil.oneBuffer(1))<0);
        }
    }

    @Test
    public void testEdgeTypeWriting() {
        for (SystemType t : SYSTEM_TYPES) {
            testEdgeTypeWriting(t.getID());
        }
        for (int i=0;i<1000;i++) {
            IDManager.VertexIDType type = random.nextDouble()<0.5? IDManager.VertexIDType.UserPropertyKey: IDManager.VertexIDType.UserEdgeLabel;
            testEdgeTypeWriting(IDManager.getSchemaId(type,random.nextInt(1000000000)));
        }
    }

    public void testEdgeTypeWriting(long etid) {
        IDHandler.DirectionID[] dir = IDManager.VertexIDType.EdgeLabel.is(etid)?
                    new IDHandler.DirectionID[]{IDHandler.DirectionID.EDGE_IN_DIR, IDHandler.DirectionID.EDGE_OUT_DIR}:
                    new IDHandler.DirectionID[]{IDHandler.DirectionID.PROPERTY_DIR};
        RelationCategory relCat = IDManager.VertexIDType.EdgeLabel.is(etid)?RelationCategory.EDGE:RelationCategory.PROPERTY;
        boolean hidden = IDManager.isSystemRelationTypeId(etid);
        for (IDHandler.DirectionID d : dir) {
            StaticBuffer b = IDHandler.getEdgeType(etid,d,hidden);
            IDHandler.EdgeTypeParse parse = IDHandler.readEdgeType(b.asReadBuffer());
            assertEquals(d,parse.dirID);
            assertEquals(etid,parse.typeId);
        }
    }

    @Test
    public void keyTest() {
        for (int t = 0; t < 1000000; t++) {
            long i = Math.abs(random.nextLong());
            assertEquals(i, IDHandler.getKeyID(IDHandler.getKey(i)));
        }
        assertEquals(Long.MAX_VALUE, IDHandler.getKeyID(IDHandler.getKey(Long.MAX_VALUE)));
    }

    public String getBuffer(ReadBuffer r) {
        String result = "";
        while (r.hasRemaining()) {
            result += getBinary(VariableLong.unsignedByte(r.getByte()),8) + " ";
        }
        return result;
    }

    public String getBinary(long id) {
        return getBinary(id,64);
    }

    public String getBinary(long id, int normalizedLength) {
        String s = Long.toBinaryString(id);
        while (s.length() < normalizedLength) {
            s = "0" + s;
        }
        return s;
    }

}
