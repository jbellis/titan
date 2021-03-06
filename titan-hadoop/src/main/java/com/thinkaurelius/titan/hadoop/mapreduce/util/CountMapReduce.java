package com.thinkaurelius.titan.hadoop.mapreduce.util;

import com.thinkaurelius.titan.hadoop.HadoopEdge;
import com.thinkaurelius.titan.hadoop.HadoopVertex;
import com.thinkaurelius.titan.hadoop.Tokens;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Vertex;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class CountMapReduce {

    public static final String CLASS = Tokens.makeNamespace(CountMapReduce.class) + ".class";

    public enum Counters {
        VERTICES_COUNTED,
        EDGES_COUNTED
    }

    public static Configuration createConfiguration(final Class<? extends Element> klass) {
        final Configuration configuration = new EmptyConfiguration();
        configuration.setClass(CLASS, klass, Element.class);
        return configuration;
    }

    public static class Map extends Mapper<NullWritable, HadoopVertex, NullWritable, LongWritable> {

        private boolean isVertex;
        private final LongWritable longWritable = new LongWritable();
        private SafeMapperOutputs outputs;

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.isVertex = context.getConfiguration().getClass(CLASS, Element.class, Element.class).equals(Vertex.class);
            this.outputs = new SafeMapperOutputs(context);
        }

        @Override
        public void map(final NullWritable key, final HadoopVertex value, final Mapper<NullWritable, HadoopVertex, NullWritable, LongWritable>.Context context) throws IOException, InterruptedException {

            if (this.isVertex) {
                final long pathCount = value.pathCount();
                this.longWritable.set(pathCount);
                context.write(NullWritable.get(), this.longWritable);
                context.getCounter(Counters.VERTICES_COUNTED).increment(pathCount > 0 ? 1 : 0);
            } else {
                long edgesCounted = 0;
                long pathCount = 0;
                for (final Edge e : value.getEdges(Direction.OUT)) {
                    final HadoopEdge edge = (HadoopEdge) e;
                    if (edge.hasPaths()) {
                        edgesCounted++;
                        pathCount = pathCount + edge.pathCount();
                    }
                }
                this.longWritable.set(pathCount);
                context.write(NullWritable.get(), this.longWritable);
                context.getCounter(Counters.EDGES_COUNTED).increment(edgesCounted);
            }

            this.outputs.write(Tokens.GRAPH, NullWritable.get(), value);
        }

        @Override
        public void cleanup(final Mapper<NullWritable, HadoopVertex, NullWritable, LongWritable>.Context context) throws IOException, InterruptedException {
            this.outputs.close();
        }
    }

    public static class Combiner extends Reducer<NullWritable, LongWritable, NullWritable, LongWritable> {

        private final LongWritable longWritable = new LongWritable();

        @Override
        public void reduce(final NullWritable key, final Iterable<LongWritable> values, final Reducer<NullWritable, LongWritable, NullWritable, LongWritable>.Context context) throws IOException, InterruptedException {
            long totalCount = 0;
            for (final LongWritable temp : values) {
                totalCount = totalCount + temp.get();
            }
            this.longWritable.set(totalCount);
            context.write(key, this.longWritable);
        }
    }

    public static class Reduce extends Reducer<NullWritable, LongWritable, NullWritable, LongWritable> {

        private SafeReducerOutputs outputs;
        private LongWritable longWritable = new LongWritable();

        @Override
        public void setup(final Reducer<NullWritable, LongWritable, NullWritable, LongWritable>.Context context) {
            this.outputs = new SafeReducerOutputs(context);
        }

        @Override
        public void reduce(final NullWritable key, final Iterable<LongWritable> values, final Reducer<NullWritable, LongWritable, NullWritable, LongWritable>.Context context) throws IOException, InterruptedException {
            long totalCount = 0;
            for (final LongWritable temp : values) {
                totalCount = totalCount + temp.get();
            }
            this.longWritable.set(totalCount);
            this.outputs.write(Tokens.SIDEEFFECT, NullWritable.get(), this.longWritable);
        }

        @Override
        public void cleanup(final Reducer<NullWritable, LongWritable, NullWritable, LongWritable>.Context context) throws IOException, InterruptedException {
            this.outputs.close();
        }
    }
}
