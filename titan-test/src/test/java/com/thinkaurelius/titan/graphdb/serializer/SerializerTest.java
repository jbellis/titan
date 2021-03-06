package com.thinkaurelius.titan.graphdb.serializer;


import com.google.common.collect.Iterables;
import com.thinkaurelius.titan.core.attribute.*;
import com.thinkaurelius.titan.diskstorage.ReadBuffer;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.graphdb.database.serialize.DataOutput;
import com.thinkaurelius.titan.graphdb.database.serialize.Serializer;
import com.thinkaurelius.titan.graphdb.database.serialize.StandardSerializer;
import com.thinkaurelius.titan.graphdb.database.serialize.attribute.*;
import com.thinkaurelius.titan.testutil.PerformanceTest;
import com.thinkaurelius.titan.testutil.RandomGenerator;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.*;

import static org.junit.Assert.*;

public class SerializerTest {

    private static final Logger log =
            LoggerFactory.getLogger(SerializerTest.class);

    Serializer serialize;
    boolean printStats;

    @Before
    public void setUp() throws Exception {
        serialize = new StandardSerializer();
        printStats = true;
    }

    @Test
    public void objectWriteRead() {
        //serialize.registerClass(short[].class);
        //serialize.registerClass(TestClass.class);
        DataOutput out = serialize.getDataOutput(128);
        String str = "This is a test";
        int i = 5;
        TestClass c = new TestClass(5, 8, new short[]{1, 2, 3, 4, 5}, TestEnum.Two);
        Number n = new Double(3.555);
        out.writeObjectNotNull(str);
        out.putInt(i);
        out.writeObject(c, TestClass.class);
        out.writeClassAndObject(n);
        ReadBuffer b = out.getStaticBuffer().asReadBuffer();
        if (printStats) log.debug(bufferStats(b));
        String str2 = serialize.readObjectNotNull(b, String.class);
        assertEquals(str, str2);
        if (printStats) log.debug(bufferStats(b));
        assertEquals(b.getInt(), i);
        TestClass c2 = serialize.readObject(b, TestClass.class);
        assertEquals(c, c2);
        if (printStats) log.debug(bufferStats(b));
        assertEquals(n, serialize.readClassAndObject(b));
        if (printStats) log.debug(bufferStats(b));
        assertFalse(b.hasRemaining());
    }

    @Test
    public void comparableStringSerialization() {
        //Characters
        DataOutput out = serialize.getDataOutput(((int) Character.MAX_VALUE) * 2 + 8);
        for (char c = Character.MIN_VALUE; c < Character.MAX_VALUE; c++) {
            out.writeObjectNotNull(Character.valueOf(c));
        }
        ReadBuffer b = out.getStaticBuffer().asReadBuffer();
        for (char c = Character.MIN_VALUE; c < Character.MAX_VALUE; c++) {
            assertEquals(c, serialize.readObjectNotNull(b, Character.class).charValue());
        }


        //String
        for (int t = 0; t < 10000; t++) {
            DataOutput out1 = serialize.getDataOutput(32 + 5);
            DataOutput out2 = serialize.getDataOutput(32 + 5);
            String s1 = RandomGenerator.randomString(1, 32);
            String s2 = RandomGenerator.randomString(1, 32);
            out1.writeObjectByteOrder(s1,String.class);
            out2.writeObjectByteOrder(s2,String.class);
            StaticBuffer b1 = out1.getStaticBuffer();
            StaticBuffer b2 = out2.getStaticBuffer();
            assertEquals(s1, serialize.readObjectByteOrder(b1.asReadBuffer(), String.class));
            assertEquals(s2, serialize.readObjectByteOrder(b2.asReadBuffer(), String.class));
            assertEquals(s1 + " vs " + s2, Integer.signum(s1.compareTo(s2)), Integer.signum(b1.compareTo(b2)));
        }
    }

    @Test
    public void classSerialization() {
        DataOutput out = serialize.getDataOutput(128);
        out.writeObjectNotNull(Boolean.class);
        out.writeObjectNotNull(Byte.class);
        out.writeObjectNotNull(Double.class);
        ReadBuffer b = out.getStaticBuffer().asReadBuffer();
        assertEquals(Boolean.class, serialize.readObjectNotNull(b, Class.class));
        assertEquals(Byte.class, serialize.readObjectNotNull(b, Class.class));
        assertEquals(Double.class, serialize.readObjectNotNull(b, Class.class));
    }

    @Test
    public void parallelDeserialization() throws InterruptedException {
        DataOutput out = serialize.getDataOutput(128);
        out.putLong(8);
        out.writeClassAndObject(Long.valueOf(8));
        TestClass c = new TestClass(5, 8, new short[]{1, 2, 3, 4, 5}, TestEnum.Two);
        out.writeObject(c, TestClass.class);
        final StaticBuffer b = out.getStaticBuffer();

        int numThreads = 1;
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int j = 0; j < 100000; j++) {
                        ReadBuffer c = b.asReadBuffer();
                        assertEquals(8, c.getLong());
                        Long l = (Long) serialize.readClassAndObject(c);
                        assertEquals(8, l.longValue());
                        TestClass c2 = serialize.readObject(c, TestClass.class);
                    }
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < numThreads; i++) {
            threads[i].join();
        }
    }

    @Test
    public void testDecimalSerializers() {
        double[] dvalues  = { 1.031, 0.031, 0.333, 3423424.771};
        Decimal.DecimalSerializer fs = new Decimal.DecimalSerializer();
        for (double d : dvalues) {
            fs.verifyAttribute(new Decimal(d));
            assertEquals(d,AbstractDecimal.convert(AbstractDecimal.convert(d,3),3),AbstractDecimal.EPSILON);
        }
        dvalues = new double[]{ 1e16f, -1e16f, AbstractDecimal.minDoubleValue(3)*10, AbstractDecimal.maxDoubleValue(3)*10};
        for (double d : dvalues) {
            try {
                fs.verifyAttribute(new Decimal(d));
                fail();
            } catch (IllegalArgumentException e) {}
        }

        dvalues = new double[]{ 0.12574, 2342332.12574, 35.123456, 24321.692953};
        Precision.PrecisionSerializer ds = new Precision.PrecisionSerializer();
        for (double d : dvalues) {
            ds.verifyAttribute(new Precision(d));
            assertEquals(d,AbstractDecimal.convert(AbstractDecimal.convert(d,6),6),AbstractDecimal.EPSILON);
        }

        dvalues = new double[]{ 1e13, -1e13, AbstractDecimal.minDoubleValue(6)*10, AbstractDecimal.maxDoubleValue(6)*10};
        for (double d : dvalues) {
            try {
                ds.verifyAttribute(new Precision(d));
                fail();
            } catch (IllegalArgumentException e) {}
        }
    }

    @Test
    public void primitiveSerialization() {
        DataOutput out = serialize.getDataOutput(128);
        out.writeObjectNotNull(Boolean.FALSE);
        out.writeObjectNotNull(Boolean.TRUE);
        out.writeObjectNotNull(Byte.MIN_VALUE);
        out.writeObjectNotNull(Byte.MAX_VALUE);
        out.writeObjectNotNull(new Byte((byte) 0));
        out.writeObjectNotNull(Short.MIN_VALUE);
        out.writeObjectNotNull(Short.MAX_VALUE);
        out.writeObjectNotNull(new Short((short) 0));
        out.writeObjectNotNull(Character.MIN_VALUE);
        out.writeObjectNotNull(Character.MAX_VALUE);
        out.writeObjectNotNull(new Character('a'));
        out.writeObjectNotNull(Integer.MIN_VALUE);
        out.writeObjectNotNull(Integer.MAX_VALUE);
        out.writeObjectNotNull(new Integer(0));
        out.writeObjectNotNull(Long.MIN_VALUE);
        out.writeObjectNotNull(Long.MAX_VALUE);
        out.writeObjectNotNull(new Long(0));
        out.writeObjectNotNull(Decimal.MIN_VALUE);
        out.writeObjectNotNull(Decimal.MAX_VALUE);
        out.writeObjectNotNull(new Float((float) 0.0));
        out.writeObjectNotNull(Precision.MIN_VALUE);
        out.writeObjectNotNull(Precision.MAX_VALUE);
        out.writeObjectNotNull(new Double(0.0));

        ReadBuffer b = out.getStaticBuffer().asReadBuffer();
        assertEquals(Boolean.FALSE, serialize.readObjectNotNull(b, Boolean.class));
        assertEquals(Boolean.TRUE, serialize.readObjectNotNull(b, Boolean.class));
        assertEquals(Byte.MIN_VALUE, serialize.readObjectNotNull(b, Byte.class).longValue());
        assertEquals(Byte.MAX_VALUE, serialize.readObjectNotNull(b, Byte.class).longValue());
        assertEquals(0, serialize.readObjectNotNull(b, Byte.class).longValue());
        assertEquals(Short.MIN_VALUE, serialize.readObjectNotNull(b, Short.class).longValue());
        assertEquals(Short.MAX_VALUE, serialize.readObjectNotNull(b, Short.class).longValue());
        assertEquals(0, serialize.readObjectNotNull(b, Short.class).longValue());
        assertEquals(Character.MIN_VALUE, serialize.readObjectNotNull(b, Character.class).charValue());
        assertEquals(Character.MAX_VALUE, serialize.readObjectNotNull(b, Character.class).charValue());
        assertEquals(new Character('a'), serialize.readObjectNotNull(b, Character.class));
        assertEquals(Integer.MIN_VALUE, serialize.readObjectNotNull(b, Integer.class).longValue());
        assertEquals(Integer.MAX_VALUE, serialize.readObjectNotNull(b, Integer.class).longValue());
        assertEquals(0, serialize.readObjectNotNull(b, Integer.class).longValue());
        assertEquals(Long.MIN_VALUE, serialize.readObjectNotNull(b, Long.class).longValue());
        assertEquals(Long.MAX_VALUE, serialize.readObjectNotNull(b, Long.class).longValue());
        assertEquals(0, serialize.readObjectNotNull(b, Long.class).longValue());
        assertEquals(Decimal.MIN_VALUE, serialize.readObjectNotNull(b, Decimal.class));
        assertEquals(Decimal.MAX_VALUE, serialize.readObjectNotNull(b, Decimal.class));
        assertEquals(0.0, serialize.readObjectNotNull(b, Float.class).floatValue(), 1e-20);
        assertEquals(Precision.MIN_VALUE, serialize.readObjectNotNull(b, Precision.class));
        assertEquals(Precision.MAX_VALUE, serialize.readObjectNotNull(b, Precision.class));
        assertEquals(0.0, serialize.readObjectNotNull(b, Double.class).doubleValue(), 1e-20);

    }


    @Test
    public void testObjectVerification() {
        Serializer s = new StandardSerializer();
        DataOutput out = s.getDataOutput(128);
        Long l = Long.valueOf(128);
        out.writeClassAndObject(l);
        Calendar c = Calendar.getInstance();
        out.writeClassAndObject(c);
        NoDefaultConstructor dc = new NoDefaultConstructor(5);
        try {
            out.writeClassAndObject(dc);
            fail();
        } catch (IllegalArgumentException e) {

        }
        TestTransientClass d = new TestTransientClass(101);
        try {
            out.writeClassAndObject(d);
            fail();
        } catch (IllegalArgumentException e) {

        }
        out.writeObject(null, TestClass.class);
    }


    @Test
    public void longWriteTest() {
        String base = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"; //26 chars
        int no = 100;
        DataOutput out = serialize.getDataOutput(128);
        for (int i = 0; i < no; i++) {
            String str = base + (i + 1);
            out.writeObjectNotNull(str);
        }
        ReadBuffer b = out.getStaticBuffer().asReadBuffer();
        if (printStats) log.debug(bufferStats(b));
        for (int i = 0; i < no; i++) {
            String str = base + (i + 1);
            String read = serialize.readObjectNotNull(b, String.class);
            assertEquals(str, read);
        }
        assertFalse(b.hasRemaining());
    }

    @Test
    public void largeWriteTest() {
        String base = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"; //26 chars
        String str = "";
        for (int i = 0; i < 100; i++) str += base;
        DataOutput out = serialize.getDataOutput(128);
        out.writeObjectNotNull(str);
        ReadBuffer b = out.getStaticBuffer().asReadBuffer();
        if (printStats) log.debug(bufferStats(b));
        assertEquals(str, serialize.readObjectNotNull(b, String.class));
        assertFalse(b.hasRemaining());
    }

    @Test
    public void enumSerializeTest() {
        DataOutput out = serialize.getDataOutput(128);
        out.writeObjectNotNull(TestEnum.Two);
        ReadBuffer b = out.getStaticBuffer().asReadBuffer();
        if (printStats) log.debug(bufferStats(b));
        assertEquals(TestEnum.Two, serialize.readObjectNotNull(b, TestEnum.class));
        assertFalse(b.hasRemaining());

    }

    @Test
    public void performanceTestLong() {
        int runs = 10000;
        printStats = false;
        PerformanceTest p = new PerformanceTest(true);
        for (int i = 0; i < runs; i++) {
            longWriteTest();
        }
        p.end();
        log.debug("LONG: Avg micro time: " + (p.getMicroTime() / runs));
    }

    @Test
    public void performanceTestShort() {
        int runs = 10000;
        printStats = false;
        PerformanceTest p = new PerformanceTest(true);
        for (int i = 0; i < runs; i++) {
            objectWriteRead();
        }
        p.end();
        log.debug("SHORT: Avg micro time: " + (p.getMicroTime() / runs));
    }

    public static String bufferStats(ReadBuffer b) {
        return "ReadBuffer length: " + b.length();
    }

    private StaticBuffer getStringBuffer(String value) {
        DataOutput o = serialize.getDataOutput(value.length()+10);
        o.writeObject(value,String.class);
        return o.getStaticBuffer();
    }

    @Test
    public void testStringCompression() {
        //ASCII encoding
        for (int t = 0; t < 100; t++) {
            String x = getRandomString(StringSerializer.TEXT_COMRPESSION_THRESHOLD-1,ASCII_VALUE);
            assertEquals(x.length()+1, getStringBuffer(x).length());
        }

        //SMAZ Encoding
//        String[] texts = {
//                "To Sherlock Holmes she is always the woman. I have seldom heard him mention her under any other name. In his eyes she eclipses and predominates the whole of her sex.",
//                "His manner was not effusive. It seldom was; but he was glad, I think, to see me. With hardly a word spoken, but with a kindly eye, he waved me to an armchair",
//                "I could not help laughing at the ease with which he explained his process of deduction.",
//                "A man entered who could hardly have been less than six feet six inches in height, with the chest and limbs of a Hercules. His dress was rich with a richness which would, in England"
//        };
//        for (String text : texts) {
//            assertTrue(text.length()> StringSerializer.TEXT_COMRPESSION_THRESHOLD);
//            StaticBuffer s = getStringBuffer(text);
////            System.out.println(String.format("String length [%s] -> byte size [%s]",text.length(),s.length()));
//            assertTrue(text.length()>s.length()); //Test that actual compression is happening
//        }

        //Gzip Encoding
        String[] patterns = { "aQd>@!as/df5h", "sdfodoiwk", "sdf", "ab", "asdfwewefefwdfkajhqwkdhj"};
        int targetLength = StringSerializer.LONG_COMPRESSION_THRESHOLD*5;
        for (String pattern : patterns) {
            StringBuilder sb = new StringBuilder(targetLength);
            for (int i=0; i<targetLength/pattern.length(); i++) sb.append(pattern);
            String text = sb.toString();
            assertTrue(text.length()> StringSerializer.LONG_COMPRESSION_THRESHOLD);
            StaticBuffer s = getStringBuffer(text);
//            System.out.println(String.format("String length [%s] -> byte size [%s]",text.length(),s.length()));
            assertTrue(text.length()>s.length()*10); //Test that radical compression is happening
        }

        for (int t = 0; t < 10000; t++) {
            String x = STRING_FACTORY.newInstance();
            DataOutput o = serialize.getDataOutput(64);
            o.writeObject(x,String.class);
            ReadBuffer r = o.getStaticBuffer().asReadBuffer();
            String y = serialize.readObject(r, String.class);
            assertEquals(x,y);
        }

    }

    @Test
    public void testSerializationMixture() {
        for (int t = 0; t < 1000; t++) {
            DataOutput out = serialize.getDataOutput(128);
            int num = random.nextInt(100)+1;
            List<SerialEntry> entries = new ArrayList<SerialEntry>(num);
            for (int i = 0; i < num; i++) {
                Map.Entry<Class,Factory> type = Iterables.get(TYPES.entrySet(),random.nextInt(TYPES.size()));
                Object element = type.getValue().newInstance();
                boolean notNull = true;
                if (random.nextDouble()<0.5) {
                    notNull = false;
                    if (random.nextDouble()<0.2) element=null;
                }
                entries.add(new SerialEntry(element,type.getKey(),notNull));
                if (notNull) out.writeObjectNotNull(element);
                else out.writeObject(element,type.getKey());
            }
            StaticBuffer sb = out.getStaticBuffer();
            ReadBuffer in = sb.asReadBuffer();
            for (SerialEntry entry : entries) {
                Object read;
                if (entry.notNull) read = serialize.readObjectNotNull(in,entry.clazz);
                else read = serialize.readObject(in,entry.clazz);
                if (entry.object==null) assertNull(read);
                else if (entry.clazz.isArray()) {
                    assertEquals(Array.getLength(entry.object),Array.getLength(read));
                    for (int i = 0; i < Array.getLength(read); i++) {
                        assertEquals(Array.get(entry.object,i),Array.get(read,i));
                    }
                } else assertEquals(entry.object,read);
            }
        }
    }

    @Test
    public void testSerializedOrder() {
        Map<Class,Factory> sortTypes = new HashMap<Class, Factory>();
        for (Map.Entry<Class,Factory> entry : TYPES.entrySet()) {
            if (serialize.isOrderPreservingDatatype(entry.getKey()))
                sortTypes.put(entry.getKey(),entry.getValue());
        }
        assertEquals(10,sortTypes.size());
        for (int t = 0; t < 3000000; t++) {
            DataOutput o1 = serialize.getDataOutput(64);
            DataOutput o2 = serialize.getDataOutput(64);
            Map.Entry<Class,Factory> type = Iterables.get(sortTypes.entrySet(),random.nextInt(sortTypes.size()));
            Comparable c1 = (Comparable)type.getValue().newInstance();
            Comparable c2 = (Comparable)type.getValue().newInstance();
            o1.writeObjectByteOrder(c1,type.getKey());
            o2.writeObjectByteOrder(c2,type.getKey());
            StaticBuffer s1 = o1.getStaticBuffer();
            StaticBuffer s2 = o2.getStaticBuffer();
            assertEquals(Math.signum(c1.compareTo(c2)),Math.signum(s1.compareTo(s2)),0.0);
            Object c1o = serialize.readObjectByteOrder(s1.asReadBuffer(),type.getKey());
            Object c2o = serialize.readObjectByteOrder(s2.asReadBuffer(),type.getKey());
            assertEquals(c1,c1o);
            assertEquals(c2,c2o);
        }


    }

    private static class SerialEntry {

        final Object object;
        final Class clazz;
        final boolean notNull;


        private SerialEntry(Object object, Class clazz, boolean notNull) {
            this.object = object;
            this.clazz = clazz;
            this.notNull = notNull;
        }
    }


    public interface Factory<T> {

        public T newInstance();

    }

    public static final Random random = new Random();

    public static final int MAX_CHAR_VALUE = 20000;
    public static final int ASCII_VALUE = 128;

    public static final String getRandomString(int maxSize, int maxChar) {
        int charOffset = 10;
        int size = random.nextInt(maxSize);
        StringBuilder sb = new StringBuilder(size);

        for (int i = 0; i < size; i++) {
            sb.append((char)(random.nextInt(maxChar-charOffset)+charOffset));
        }
        return sb.toString();
    }


    public static final Factory<String> STRING_FACTORY = new Factory<String>() {
        @Override
        public String newInstance() {
            if (random.nextDouble()>0.1) {
                return getRandomString(StringSerializer.TEXT_COMRPESSION_THRESHOLD*2,
                        random.nextDouble()>0.5?ASCII_VALUE:MAX_CHAR_VALUE);
            } else {
                return getRandomString(StringSerializer.LONG_COMPRESSION_THRESHOLD*4,
                        random.nextDouble()>0.5?ASCII_VALUE:MAX_CHAR_VALUE);
            }
        }
    };

    public static final float randomGeoPoint() {
        return random.nextFloat()*180.0f-90.0f;
    }

    public static Map<Class,Factory> TYPES = new HashMap<Class,Factory>() {{
        put(Byte.class, new Factory<Byte>() {
            @Override
            public Byte newInstance() {
                return (byte)random.nextInt();
            }
        });
        put(Short.class, new Factory<Short>() {
            @Override
            public Short newInstance() {
                return (short)random.nextInt();
            }
        });
        put(Integer.class, new Factory<Integer>() {
            @Override
            public Integer newInstance() {
                return random.nextInt();
            }
        });
        put(Long.class, new Factory<Long>() {
            @Override
            public Long newInstance() {
                return random.nextLong();
            }
        });
        put(Boolean.class, new Factory<Boolean>() {
            @Override
            public Boolean newInstance() {
                return random.nextInt(2)==0;
            }
        });
        put(Character.class, new Factory<Character>() {
            @Override
            public Character newInstance() {
                return (char)random.nextInt();
            }
        });
        put(Decimal.class, new Factory<Decimal>() {
            @Override
            public Decimal newInstance() {
                return new Decimal(random.nextInt()*1.0/1000);
            }
        });
        put(Precision.class, new Factory<Precision>() {
            @Override
            public Precision newInstance() {
                return new Precision(random.nextInt()*1.0/1000000.0);
            }
        });
        put(Date.class, new Factory<Date>() {
            @Override
            public Date newInstance() {
                return new Date(random.nextLong());
            }
        });
        put(Float.class, new Factory<Float>() {
            @Override
            public Float newInstance() {
                return random.nextFloat()*10000 - 10000/2.0f;
            }
        });
        put(Double.class, new Factory<Double>() {
            @Override
            public Double newInstance() {
                return random.nextDouble()*10000000 - 10000000/2.0;
            }
        });
        put(Geoshape.class, new Factory<Geoshape>() {
            @Override
            public Geoshape newInstance() {
                if (random.nextDouble()>0.5)
                    return Geoshape.box(randomGeoPoint(),randomGeoPoint(),randomGeoPoint(),randomGeoPoint());
                else
                    return Geoshape.circle(randomGeoPoint(),randomGeoPoint(),random.nextInt(100)+1);
            }
        });
        put(String.class, STRING_FACTORY);
        put(boolean[].class,getArrayFactory(boolean.class,get(Boolean.class)));
        put(byte[].class,getArrayFactory(byte.class,get(Byte.class)));
        put(short[].class,getArrayFactory(short.class,get(Short.class)));
        put(int[].class,getArrayFactory(int.class,get(Integer.class)));
        put(long[].class,getArrayFactory(long.class,get(Long.class)));
        put(float[].class,getArrayFactory(float.class,get(Float.class)));
        put(double[].class,getArrayFactory(double.class,get(Double.class)));
        put(char[].class,getArrayFactory(char.class,get(Character.class)));
        put(String[].class,getArrayFactory(String.class,get(String.class)));
        put(TestClass.class,new Factory<TestClass>() {
            @Override
            public TestClass newInstance() {
                return new TestClass(random.nextLong(),random.nextLong(),new short[]{1,2,3},TestEnum.Two);
            }
        });
    }};

    private static Factory getArrayFactory(final Class ct, final Factory f) {
        return new Factory() {
            @Override
            public Object newInstance() {
                int length = random.nextInt(100);
                Object array = Array.newInstance(ct,length);
                for (int i = 0; i < length; i++) {
                    if (ct==boolean.class) Array.setBoolean(array,i, (Boolean) f.newInstance());
                    else if (ct==byte.class) Array.setByte(array,i, (Byte) f.newInstance());
                    else if (ct==short.class) Array.setShort(array,i, (Short) f.newInstance());
                    else if (ct==int.class) Array.setInt(array,i, (Integer) f.newInstance());
                    else if (ct==long.class) Array.setLong(array,i, (Long) f.newInstance());
                    else if (ct==float.class) Array.setFloat(array,i, (Float) f.newInstance());
                    else if (ct==double.class) Array.setDouble(array,i, (Double) f.newInstance());
                    else if (ct==char.class) Array.setChar(array,i, (Character) f.newInstance());
                    else Array.set(array,i, f.newInstance());
                }
                return array;
            }
        };
    }




    //Arrays (support null serialization)



}

